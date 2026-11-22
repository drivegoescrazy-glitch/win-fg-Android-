// win-fg — no-root MediaProjection capture + system-overlay renderer.
//
// This is deliberately NOT a Vulkan layer. Android does not allow a normal app
// to inject a layer into another app's process, so the supported no-root path
// captures the composited display and presents frame-generated frames in our
// own overlay. FrameGen is the same compute engine used by the layer; only the
// source and destination differ.
//
// Pipeline per captured frame:
//   AHardwareBuffer -> curr image -> FrameGen(prev,curr) -> generated image
//   generated -> spare swapchain image, curr -> real swapchain image
//   present generated, then real (2x when the display has refresh headroom)
//
// The Java service marks the overlay window with SurfaceFlinger's
// skip-screenshot flag so MediaProjection does not recursively capture the
// overlay. FLAG_SECURE is deliberately not used: MediaProjection renders a
// secure window as opaque black, which would hide the game.
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include "config.hpp"
#include "framegen.hpp"
#include "vk_dispatch.hpp"

#include <algorithm>
#include <chrono>
#include <atomic>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <pthread.h>
#include <ucontext.h>
#include <unistd.h>
#include <vector>

#define LOG_TAG "win-fg-overlay"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// A Vulkan fault inside the Adreno driver cannot be caught, but the signal and the
// stack can be written to files/win-fg/native_crash.log, which the launcher shows on
// the next start. Without this the only evidence of a renderer crash is a vanished
// notification, because the process (and the foreground service) dies with it.
char g_crashPath[256];
std::atomic<bool> g_crashHandlerInstalled{false};
/** Names the renderer stage in progress; written into the next crash report. */
const char* volatile g_stage = "startup";

void stage(const char* name) { g_stage = name; }

void writeAll(int fd, const char* text) {
    if (fd < 0 || text == nullptr) return;
    ssize_t ignored = write(fd, text, strlen(text));
    (void) ignored;
}

/** Module + offset: absolute addresses alone are unusable after ASLR. */
void writeFrame(int fd, const char* tag, void* address) {
    if (address == nullptr) return;
    Dl_info info{};
    char line[600];
    int n;
    if (dladdr(address, &info) != 0 && info.dli_fname != nullptr) {
        unsigned long offset = (unsigned long) address - (unsigned long) info.dli_fbase;
        n = snprintf(line, sizeof line, "%s %p  %s+0x%lx\n", tag, address,
                     info.dli_fname, offset);
    } else {
        n = snprintf(line, sizeof line, "%s %p  <unknown module>\n", tag, address);
    }
    if (n > 0) {
        ssize_t ignored = write(fd, line, (size_t) n);
        (void) ignored;
    }
}

void nativeCrashHandler(int sig, siginfo_t* info, void* rawContext) {
    int fd = g_crashPath[0] ? open(g_crashPath, O_WRONLY | O_CREAT | O_APPEND, 0600) : -1;
    if (fd >= 0) {
        char threadName[32] = "?";
        pthread_getname_np(pthread_self(), threadName, sizeof threadName);
        char head[256];
        int n = snprintf(head, sizeof head,
                         "native signal %d code=%d addr=%p thread=%s t=%ld\n",
                         sig, info ? info->si_code : 0, info ? info->si_addr : nullptr,
                         threadName, (long) time(nullptr));
        if (n > 0) write(fd, head, (size_t) n);
        writeAll(fd, "stage: ");
        writeAll(fd, g_stage);
        writeAll(fd, "\n");

        auto* uc = static_cast<ucontext_t*>(rawContext);
        if (uc != nullptr) {
            // The faulting instruction itself, plus its caller. Unwinding from inside a
            // signal handler with _Unwind_Backtrace() throws on bionic and aborts the
            // process, which destroyed the evidence last time.
            uintptr_t pc = uc->uc_mcontext.pc;
            uintptr_t lr = uc->uc_mcontext.regs[30];
            uintptr_t sp = uc->uc_mcontext.sp;
            uintptr_t fp = uc->uc_mcontext.regs[29];
            writeFrame(fd, "pc", (void*) pc);
            writeFrame(fd, "lr", (void*) lr);
            char regs[96];
            int len = snprintf(regs, sizeof regs, "sp=%p fp=%p\n", (void*) sp, (void*) fp);
            if (len > 0) write(fd, regs, (size_t) len);
            // Walk the frame-pointer chain, but only inside this thread's own stack, so
            // a corrupt frame pointer cannot fault the handler as well.
            for (int i = 0; i < 24 && fp != 0; ++i) {
                if ((fp & 7) != 0 || fp < sp || fp > sp + (2u << 20)) break;
                auto* frame = reinterpret_cast<uintptr_t*>(fp);
                uintptr_t ret = frame[1];
                writeFrame(fd, "  bt", (void*) ret);
                uintptr_t next = frame[0];
                if (next <= fp) break;
                fp = next;
            }
        }
        close(fd);
    }
    // Restore the default action and re-raise so Android still records the crash.
    signal(sig, SIG_DFL);
    raise(sig);
}

void installSignalHandlers(const char* dir) {
    if (g_crashHandlerInstalled.exchange(true)) return;
    snprintf(g_crashPath, sizeof g_crashPath, "%s/native_crash.log", dir);
    struct sigaction sa {};
    sa.sa_sigaction = nativeCrashHandler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (int sig : {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT}) sigaction(sig, &sa, nullptr);
    LOGI("native crash handler armed at %s", g_crashPath);
}

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

struct AhbImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
};

class Renderer {
public:
    bool init(ANativeWindow* window, int width, int height, float refreshHz,
              bool frameGen, const winfg::Config& cfg);
    bool submit(AHardwareBuffer* buffer, uint64_t timestampNs);
    bool dead() const { return dead_; }

    /** 9 ints: generated, fgOff, first, noSpare, noInterval, spareFail, passthrough,
     *  lastDeltaMs (negative = below the insert threshold), lastVsyncMs. */
    int lastPresents() const { return lastPresents_; }

    /** Presents the generated frame held back by the last submit, if any. */
    void presentPending() {
        std::lock_guard<std::mutex> lk(pendingMutex_);
        if (!pendingValid_) return;
        pendingValid_ = false;
        if (swapchain_ == VK_NULL_HANDLE || pendingSem_ == VK_NULL_HANDLE
                || pendingIndex_ >= swapImages_.size()) return;
        VkPresentInfoKHR psi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        psi.waitSemaphoreCount = 1;
        psi.pWaitSemaphores = &pendingSem_;
        psi.swapchainCount = 1;
        psi.pSwapchains = &swapchain_;
        psi.pImageIndices = &pendingIndex_;
        VkResult pr = vkQueuePresentKHR(queue_, &psi);
        if (pr != VK_SUCCESS) LOGW("present generated failed: %d", (int)pr);
    }

    void readStats(int32_t* out, int n) const {
        const int32_t v[10] = { stGenerated_, stFgOff_, stFirst_, stNoSpare_, stNoInterval_,
                                stSpareFail_, stPass_, stDeltaMs_, stVsyncMs_, lastReason_ };
        for (int i = 0; i < 10 && i < n; ++i) out[i] = v[i];
    }
    void destroy();

private:
    bool createInstance(ANativeWindow* window);
    bool pickDevice();
    bool createDevice();
    bool createSwapchain();
    bool createOffscreen();
    bool importAhb(AHardwareBuffer* buffer, int width, int height, AhbImage& out);
    void destroyAhb(AhbImage& image);
    void destroyOffscreen();
    void destroySwapchain();
    void resize(int width, int height);
    bool makeImage(Image& out, VkExtent2D extent, VkFormat format, VkImageUsageFlags usage);
    void destroyImage(Image& image);
    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags props) const;
    void barrier(VkCommandBuffer cmd, VkImage image,
                 VkImageLayout oldLayout, VkImageLayout newLayout,
                 VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                 VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage,
                 uint32_t srcQueue = VK_QUEUE_FAMILY_IGNORED,
                 uint32_t dstQueue = VK_QUEUE_FAMILY_IGNORED);
    void blit(VkCommandBuffer cmd, VkImage src, VkExtent2D srcExtent,
              VkImage dst, VkExtent2D dstExtent, VkFilter filter);
    void waitFence();
    bool hasExtension(const char* name) const;

    ANativeWindow* window_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkPhysicalDeviceMemoryProperties memProps_{};
    winfg::InstanceDispatch id_{};
    winfg::DeviceDispatch dd_{};
    winfg::FrameGen fg_{};
    winfg::Config cfg_{};
    bool fgReady_ = false;
    /** false = mirror the capture only (frame generation crashed on this driver). */
    bool frameGen_ = true;
    bool foreignQueue_ = false;
    /** Driver reported the surface/device gone; this session cannot present again. */
    bool dead_ = false;
    float refreshHz_ = 60.0f;
    uint64_t lastCaptureNs_ = 0;
    // Renderer-side clock, used when the capture timestamps are unusable (some
    // producers repeat or reset them). Without this fallback the insert decision
    // below can never pass and the overlay keeps showing the game's own rate.
    uint64_t lastHostNs_ = 0;
    // Insert-decision telemetry, exposed to the service through nativeStats().
    int32_t stGenerated_ = 0, stFgOff_ = 0, stFirst_ = 0, stNoSpare_ = 0,
            stNoInterval_ = 0, stSpareFail_ = 0, stPass_ = 0;
    int32_t stDeltaMs_ = 0, stVsyncMs_ = 0;
    // Deferred generated present (even cadence).
    std::mutex pendingMutex_;
    uint32_t pendingIndex_ = 0;
    VkSemaphore pendingSem_ = VK_NULL_HANDLE;
    bool pendingValid_ = false;
    /** Presents performed by the last submit: 2 with a generated frame, else 1. */
    int32_t lastPresents_ = 1;
    /** Why this capture produced only one present: 0 generated, 1 fg off, 2 first
     *  frame, 3 no spare image, 4 interval below threshold, 5 spare acquire failed. */
    int32_t lastReason_ = 0;
    uint64_t presented_ = 0;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{0, 0};
    std::vector<VkImage> swapImages_;

    VkCommandPool cmdPool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    bool fenceInFlight_ = false;
    VkSemaphore acquireReal_ = VK_NULL_HANDLE;
    VkSemaphore acquireSpare_ = VK_NULL_HANDLE;
    VkSemaphore renderReal_ = VK_NULL_HANDLE;
    VkSemaphore renderSpare_ = VK_NULL_HANDLE;

    Image prev_{};
    Image curr_{};
    Image generated_{};
    bool prevValid_ = false;

    PFN_vkCreateAndroidSurfaceKHR createAndroidSurface_ = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProps_ = nullptr;
    std::mutex lock_;
};

uint32_t Renderer::memoryType(uint32_t bits, VkMemoryPropertyFlags props) const {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) &&
            (memProps_.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; ++i) {
        if (bits & (1u << i)) return i;
    }
    return 0;
}

bool Renderer::hasExtension(const char* name) const {
    if (physical_ == VK_NULL_HANDLE) return false;
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(physical_, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> props(count);
    vkEnumerateDeviceExtensionProperties(physical_, nullptr, &count, props.data());
    for (const auto& p : props) {
        if (std::strcmp(p.extensionName, name) == 0) return true;
    }
    return false;
}

bool Renderer::createInstance(ANativeWindow* window) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Win-FG overlay";
    app.applicationVersion = 1;
    app.pEngineName = "win-fg";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_1;

    const char* extensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = extensions;
    if (vkCreateInstance(&ci, nullptr, &instance_) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        return false;
    }

    createAndroidSurface_ = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
            vkGetInstanceProcAddr(instance_, "vkCreateAndroidSurfaceKHR"));
    if (!createAndroidSurface_) {
        LOGE("vkCreateAndroidSurfaceKHR missing");
        return false;
    }
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = window;
    if (createAndroidSurface_(instance_, &sci, nullptr, &surface_) != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed");
        return false;
    }

    id_.GetInstanceProcAddr = vkGetInstanceProcAddr;
    id_.DestroyInstance = vkDestroyInstance;
    id_.GetPhysicalDeviceMemoryProperties = vkGetPhysicalDeviceMemoryProperties;
    id_.GetPhysicalDeviceQueueFamilyProperties = vkGetPhysicalDeviceQueueFamilyProperties;
    id_.GetPhysicalDeviceProperties = vkGetPhysicalDeviceProperties;
    id_.GetPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vkGetInstanceProcAddr(instance_, "vkGetPhysicalDeviceProperties2"));
    id_.GetPhysicalDeviceSurfaceCapabilitiesKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
                    vkGetInstanceProcAddr(instance_, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
    return true;
}

bool Renderer::pickDevice() {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance_, &count, nullptr) != VK_SUCCESS || count == 0) {
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    for (VkPhysicalDevice dev : devices) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        if (qCount == 0) continue;
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, qProps.data());

        uint32_t qf = UINT32_MAX;
        for (uint32_t i = 0; i < qCount; ++i) {
            VkBool32 present = VK_FALSE;
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) &&
                (qProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface_, &present);
                if (present) { qf = i; break; }
            }
        }
        if (qf == UINT32_MAX) continue;

        physical_ = dev;
        queueFamily_ = qf;
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(physical_, &props);
        LOGI("device: %s api=%u.%u.%u", props.deviceName,
             VK_API_VERSION_MAJOR(props.apiVersion),
             VK_API_VERSION_MINOR(props.apiVersion),
             VK_API_VERSION_PATCH(props.apiVersion));
        return true;
    }
    return false;
}

bool Renderer::createDevice() {
    if (!hasExtension("VK_ANDROID_external_memory_android_hardware_buffer")) {
        LOGE("VK_ANDROID_external_memory_android_hardware_buffer unavailable");
        return false;
    }

    std::vector<const char*> extensions = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            "VK_ANDROID_external_memory_android_hardware_buffer",
            VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    };
    // YCbCr conversion is optional; the RGBA capture path does not need it.
    // Enabling an unsupported extension makes vkCreateDevice fail outright,
    // which was a silent overlay-start failure on some Mali/Adreno drivers.
    if (hasExtension(VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME))
        extensions.push_back(VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME);
    foreignQueue_ = hasExtension(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    if (foreignQueue_) extensions.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    LOGI("device extensions: %zu (ycbcr=%d foreign=%d)", extensions.size(),
         (int)hasExtension(VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME), (int)foreignQueue_);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;

    VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    ci.queueCreateInfoCount = 1;
    ci.pQueueCreateInfos = &qci;
    ci.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    ci.ppEnabledExtensionNames = extensions.data();
    if (vkCreateDevice(physical_, &ci, nullptr, &device_) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        return false;
    }
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    vkGetPhysicalDeviceMemoryProperties(physical_, &memProps_);

    getAhbProps_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!getAhbProps_) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID missing");
        return false;
    }

    dd_.GetDeviceProcAddr = vkGetDeviceProcAddr;
#define LOAD(fn) dd_.fn = reinterpret_cast<PFN_vk##fn>(vkGetDeviceProcAddr(device_, "vk" #fn))
    LOAD(DestroyDevice); LOAD(GetDeviceQueue); LOAD(QueueSubmit); LOAD(QueueWaitIdle);
    LOAD(DeviceWaitIdle); LOAD(QueueSubmit2); LOAD(QueueSubmit2KHR);
    LOAD(CreateSwapchainKHR); LOAD(DestroySwapchainKHR); LOAD(GetSwapchainImagesKHR);
    LOAD(AcquireNextImageKHR); LOAD(AcquireNextImage2KHR); LOAD(QueuePresentKHR);
    LOAD(CreateImage); LOAD(DestroyImage); LOAD(CreateImageView); LOAD(DestroyImageView);
    LOAD(AllocateMemory); LOAD(FreeMemory); LOAD(BindImageMemory); LOAD(GetImageMemoryRequirements);
    LOAD(CreateBuffer); LOAD(DestroyBuffer); LOAD(GetBufferMemoryRequirements);
    LOAD(BindBufferMemory); LOAD(MapMemory); LOAD(UnmapMemory);
    LOAD(CreateSampler); LOAD(DestroySampler);
    LOAD(CreateShaderModule); LOAD(DestroyShaderModule);
    LOAD(CreateDescriptorSetLayout); LOAD(DestroyDescriptorSetLayout);
    LOAD(CreatePipelineLayout); LOAD(DestroyPipelineLayout);
    LOAD(CreateComputePipelines); LOAD(DestroyPipeline);
    LOAD(CreateDescriptorPool); LOAD(DestroyDescriptorPool); LOAD(ResetDescriptorPool);
    LOAD(AllocateDescriptorSets); LOAD(UpdateDescriptorSets);
    LOAD(CreateCommandPool); LOAD(DestroyCommandPool);
    LOAD(AllocateCommandBuffers); LOAD(FreeCommandBuffers);
    LOAD(BeginCommandBuffer); LOAD(EndCommandBuffer);
    LOAD(CmdBindPipeline); LOAD(CmdBindDescriptorSets); LOAD(CmdDispatch);
    LOAD(CmdPipelineBarrier); LOAD(CmdCopyImage); LOAD(CmdCopyImageToBuffer);
    LOAD(CmdBlitImage); LOAD(CmdClearColorImage);
    LOAD(CreateFence); LOAD(DestroyFence); LOAD(WaitForFences); LOAD(ResetFences);
    LOAD(CreateSemaphore); LOAD(DestroySemaphore);
#undef LOAD
    return true;
}

bool Renderer::makeImage(Image& out, VkExtent2D extent, VkFormat format, VkImageUsageFlags usage) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = format;
    ci.extent = {extent.width, extent.height, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &ci, nullptr, &out.image) != VK_SUCCESS) {
        LOGE("vkCreateImage failed (%ux%u fmt=%d)", extent.width, extent.height, (int)format);
        return false;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(device_, out.image, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vkAllocateMemory(device_, &ai, nullptr, &out.mem) != VK_SUCCESS ||
        vkBindImageMemory(device_, out.image, out.mem, 0) != VK_SUCCESS) {
        LOGE("image allocation failed");
        return false;
    }
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = out.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(device_, &vi, nullptr, &out.view) != VK_SUCCESS) {
        LOGE("vkCreateImageView failed");
        return false;
    }
    return true;
}

void Renderer::destroyImage(Image& image) {
    if (!device_) {
        image = Image{};
        return;
    }
    if (image.view) vkDestroyImageView(device_, image.view, nullptr);
    if (image.image) vkDestroyImage(device_, image.image, nullptr);
    if (image.mem) vkFreeMemory(device_, image.mem, nullptr);
    image = Image{};
}

bool Renderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps{};
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &caps) != VK_SUCCESS) {
        LOGE("surface caps failed");
        return false;
    }
    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount, nullptr);
    if (formatCount == 0) {
        LOGE("no surface formats");
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount, formats.data());
    VkSurfaceFormatKHR chosen = formats[0];
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM) { chosen = f; break; }
    }
    if (chosen.format == VK_FORMAT_UNDEFINED) {
        chosen.format = VK_FORMAT_R8G8B8A8_UNORM;
        chosen.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    }

    uint32_t modeCount = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &modeCount, nullptr);
    std::vector<VkPresentModeKHR> modes(modeCount);
    if (modeCount) vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &modeCount, modes.data());
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    for (VkPresentModeKHR m : modes) {
        if (m == VK_PRESENT_MODE_FIFO_KHR) { presentMode = m; break; }
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) extent = {static_cast<uint32_t>(extent_.width),
                                               static_cast<uint32_t>(extent_.height)};
    if (extent.width == 0 || extent.height == 0) {
        LOGE("invalid swapchain extent");
        return false;
    }
    const VkImageUsageFlags wantedUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                          VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if ((caps.supportedUsageFlags & wantedUsage) != wantedUsage) {
        LOGE("surface does not support blit-to-swapchain (supported=0x%x)",
             (unsigned)caps.supportedUsageFlags);
        return false;
    }
    // Allocate the spare up front. Frame insertion needs a swapchain image that the
    // compositor is not currently holding; asking for the surface minimum leaves the
    // pool exactly consumed by the display queue, so AcquireNextImageKHR for the
    // generated present can only ever fail and the overlay silently degrades to one
    // present per capture (30 -> 30). Two images above the minimum, never fewer than
    // four, and clamped to what the surface will actually grant.
    uint32_t minImages = caps.minImageCount + 2u;
    if (minImages < 4u) minImages = 4u;
    if (caps.maxImageCount != 0 && minImages > caps.maxImageCount) minImages = caps.maxImageCount;
    if (minImages < 2u) minImages = 2u;

    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)) {
        if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
            alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR)
            alpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
        else
            alpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
    }

    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = surface_;
    ci.minImageCount = minImages;
    ci.imageFormat = chosen.format;
    ci.imageColorSpace = chosen.colorSpace;
    ci.imageExtent = extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = caps.currentTransform;
    ci.compositeAlpha = alpha;
    ci.presentMode = presentMode;
    ci.clipped = VK_TRUE;
    ci.oldSwapchain = VK_NULL_HANDLE;
    if (vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_) != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed");
        return false;
    }
    uint32_t imageCount = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
    swapImages_.resize(imageCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, swapImages_.data());
    swapFormat_ = chosen.format;
    extent_ = extent;
    LOGI("swapchain %ux%u fmt=%d images=%u mode=%d", extent.width, extent.height,
         (int)swapFormat_, imageCount, (int)presentMode);
    return true;
}

void Renderer::destroySwapchain() {
    if (swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
    swapImages_.clear();
}

bool Renderer::createOffscreen() {
    destroyOffscreen();
    const VkImageUsageFlags sampled = VK_IMAGE_USAGE_SAMPLED_BIT |
                                       VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                       VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (!makeImage(prev_, extent_, VK_FORMAT_R8G8B8A8_UNORM, sampled)) return false;
    if (!makeImage(curr_, extent_, VK_FORMAT_R8G8B8A8_UNORM, sampled)) return false;
    if (!makeImage(generated_, extent_, VK_FORMAT_R8G8B8A8_UNORM,
                   VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        return false;
    }
    prevValid_ = false;
    return true;
}

void Renderer::destroyOffscreen() {
    destroyImage(prev_);
    destroyImage(curr_);
    destroyImage(generated_);
    prevValid_ = false;
}

void Renderer::resize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    if (static_cast<uint32_t>(width) == extent_.width &&
        static_cast<uint32_t>(height) == extent_.height) {
        // Same size: still force a fresh swapchain when called after an
        // OUT_OF_DATE acquire. The caller sets extent_ to zero first.
        if (swapchain_ != VK_NULL_HANDLE) return;
    }
    vkDeviceWaitIdle(device_);
    destroySwapchain();
    extent_ = {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
    if (!createSwapchain()) {
        // Retrying every frame would allocate a swapchain per capture; let the
        // Java side rebuild the window and the renderer instead.
        dead_ = true;
        return;
    }
    if (fgReady_) fg_.onResize(extent_, VK_FORMAT_R8G8B8A8_UNORM, true);
    createOffscreen();
}

bool Renderer::init(ANativeWindow* window, int width, int height, float refreshHz,
                    bool frameGen, const winfg::Config& cfg) {
    window_ = window;
    cfg_ = cfg;
    frameGen_ = frameGen;
    refreshHz_ = (refreshHz > 1.0f && refreshHz < 1000.0f) ? refreshHz : 60.0f;
    extent_ = {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
    stage("create instance");
    if (!createInstance(window_)) return false;
    stage("pick device");
    if (!pickDevice()) return false;
    stage("create device");
    if (!createDevice()) return false;

    if (frameGen_) {
        stage("framegen init");
        if (!fg_.init(&dd_, &id_, physical_, device_, queueFamily_, queue_)) {
            // Degrade, never die: an engine that cannot initialise must still leave
            // the user with a working (mirror) overlay rather than no overlay at all.
            LOGE("FrameGen init failed — running mirror-only");
            stage("mirror fallback (framegen init)");
            fgReady_ = false;
        } else {
            fgReady_ = true;
            fg_.configure(cfg_);
        }
    }
    stage("create swapchain");
    if (!createSwapchain()) return false;
    if (frameGen_ && fgReady_) {
        stage("framegen resize");
        if (!fg_.onResize(extent_, VK_FORMAT_R8G8B8A8_UNORM)) {
            LOGE("FrameGen resize failed — running mirror-only");
            stage("mirror fallback (framegen resize)");
            fgReady_ = false;
        }
    }
    stage("create offscreen");
    if (!createOffscreen()) return false;
    stage("create sync");

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = queueFamily_;
    if (vkCreateCommandPool(device_, &pci, nullptr, &cmdPool_) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbi.commandPool = cmdPool_;
    cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbi.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device_, &cbi, &cmd_) != VK_SUCCESS) return false;

    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(device_, &fi, nullptr, &fence_) != VK_SUCCESS) return false;
    VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (VkSemaphore* s : {&acquireReal_, &acquireSpare_, &renderReal_, &renderSpare_}) {
        if (vkCreateSemaphore(device_, &si, nullptr, s) != VK_SUCCESS) return false;
    }
    stage("idle");
    LOGI("overlay renderer ready (%dx%d, %s)", width, height,
         frameGen_ ? "2x insertion" : "mirror only");
    return true;
}

bool Renderer::importAhb(AHardwareBuffer* buffer, int width, int height, AhbImage& out) {
    if (!getAhbProps_ || width <= 0 || height <= 0) return false;

    // VUID-vkGetAndroidHardwareBufferPropertiesANDROID-buffer-01884: the buffer
    // must carry at least one AHARDWAREBUFFER_USAGE_GPU_* flag. A CPU-only
    // buffer (a plain ImageReader without GPU usage) is undefined behaviour here
    // and faults inside the Adreno driver, so check before touching Vulkan.
    AHardwareBuffer_Desc usageDesc{};
    AHardwareBuffer_describe(buffer, &usageDesc);
    const uint64_t gpuUsage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                              AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                              AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                              AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
    if ((usageDesc.usage & gpuUsage) == 0) {
        LOGW("AHB has no GPU usage (usage=0x%llx); refusing to import",
             (unsigned long long)usageDesc.usage);
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &formatProps;
    if (getAhbProps_(device_, buffer, &props) != VK_SUCCESS) {
        LOGW("AHB properties failed");
        return false;
    }
    // MediaProjection's ImageReader with PixelFormat.RGBA_8888 produces
    // AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM. Some drivers (including the
    // emulator's SwiftShader) report that native format as externalFormat=1
    // while still returning a valid VkFormat. Only reject when Vulkan has no
    // native format for the buffer.
    if (formatProps.format == VK_FORMAT_UNDEFINED) {
        LOGW("AHB format unsupported (external=%llu)",
             (unsigned long long)formatProps.externalFormat);
        return false;
    }

    VkExternalMemoryImageCreateInfo external{
            VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.pNext = &external;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = formatProps.format;
    ci.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &ci, nullptr, &out.image) != VK_SUCCESS) {
        LOGW("AHB image import failed");
        return false;
    }

    VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = out.image;
    VkImportAndroidHardwareBufferInfoANDROID import{
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    import.buffer = buffer;
    import.pNext = &dedicated;

    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.pNext = &import;
    ai.allocationSize = props.allocationSize;
    ai.memoryTypeIndex = memoryType(props.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vkAllocateMemory(device_, &ai, nullptr, &out.mem) != VK_SUCCESS ||
        vkBindImageMemory(device_, out.image, out.mem, 0) != VK_SUCCESS) {
        LOGW("AHB memory import failed");
        if (out.mem) vkFreeMemory(device_, out.mem, nullptr);
        if (out.image) vkDestroyImage(device_, out.image, nullptr);
        out = AhbImage{};
        return false;
    }
    return true;
}

void Renderer::destroyAhb(AhbImage& image) {
    if (image.image) vkDestroyImage(device_, image.image, nullptr);
    if (image.mem) vkFreeMemory(device_, image.mem, nullptr);
    image = AhbImage{};
}

void Renderer::barrier(VkCommandBuffer cmd, VkImage image,
                       VkImageLayout oldLayout, VkImageLayout newLayout,
                       VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                       VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage,
                       uint32_t srcQueue, uint32_t dstQueue) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = oldLayout;
    b.newLayout = newLayout;
    b.srcQueueFamilyIndex = srcQueue;
    b.dstQueueFamilyIndex = dstQueue;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void Renderer::blit(VkCommandBuffer cmd, VkImage src, VkExtent2D srcExtent,
                    VkImage dst, VkExtent2D dstExtent, VkFilter filter) {
    VkImageBlit b{};
    b.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    b.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    b.srcOffsets[1] = {static_cast<int32_t>(srcExtent.width),
                       static_cast<int32_t>(srcExtent.height), 1};
    b.dstOffsets[1] = {static_cast<int32_t>(dstExtent.width),
                       static_cast<int32_t>(dstExtent.height), 1};
    vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &b, filter);
}

void Renderer::waitFence() {
    if (fenceInFlight_) {
        vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX);
        fenceInFlight_ = false;
    }
}

bool Renderer::submit(AHardwareBuffer* buffer, uint64_t timestampNs) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!device_ || dead_ || !buffer || swapchain_ == VK_NULL_HANDLE) return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    int w = static_cast<int>(desc.width);
    int h = static_cast<int>(desc.height);
    if (w != static_cast<int>(extent_.width) || h != static_cast<int>(extent_.height)) {
        resize(w, h);
        if (swapchain_ == VK_NULL_HANDLE || !prev_.image || !curr_.image || !generated_.image) {
            return false;
        }
    }

    stage("frame import");
    AhbImage ahb{};
    if (!importAhb(buffer, w, h, ahb)) return false;
    stage("frame acquire");

    waitFence();
    vkResetFences(device_, 1, &fence_);

    // Never wait forever for an image. If the present queue is saturated (the game
    // already runs at the display rate, or SurfaceFlinger stalls), a blocking acquire
    // freezes this thread while it holds the renderer lock; the main thread then
    // blocks on the same lock and the app is ANR-killed. Dropping a frame is safe.
    uint32_t realIndex = 0;
    VkResult ar = vkAcquireNextImageKHR(device_, swapchain_, 250000000ull,
                                        acquireReal_, VK_NULL_HANDLE, &realIndex);
    if (ar == VK_TIMEOUT || ar == VK_NOT_READY) {
        destroyAhb(ahb);
        return false;
    }
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) {
        destroyAhb(ahb);
        int w0 = static_cast<int>(extent_.width);
        int h0 = static_cast<int>(extent_.height);
        extent_ = {0, 0};
        resize(w0, h0);
        return false;
    }
    if (ar == VK_ERROR_SURFACE_LOST_KHR || ar == VK_ERROR_DEVICE_LOST) {
        destroyAhb(ahb);
        dead_ = true;
        LOGE("swapchain lost on acquire: %d", (int)ar);
        return false;
    }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        destroyAhb(ahb);
        LOGW("acquire real failed: %d", (int)ar);
        return false;
    }
    if (realIndex >= swapImages_.size()) {
        destroyAhb(ahb);
        LOGW("acquire returned out-of-range image %u", realIndex);
        return false;
    }

    // Insert a generated frame only when the capture rate leaves a free vsync slot,
    // i.e. the source runs slower than the display. Presenting two images per capture
    // unconditionally asks a 60 Hz panel for 120 fps; the swapchain queue then backs
    // up and the pipeline stalls. (At 60 fps capture/60 Hz display there is nothing
    // to double, at 30 fps capture there is.)
    uint64_t deltaNs = (lastCaptureNs_ != 0 && timestampNs > lastCaptureNs_)
            ? timestampNs - lastCaptureNs_ : 0;
    lastCaptureNs_ = timestampNs;
    {
        const uint64_t nowHost = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count());
        const uint64_t hostDelta = (lastHostNs_ != 0 && nowHost > lastHostNs_)
                ? nowHost - lastHostNs_ : 0;
        lastHostNs_ = nowHost;
        // A missing, duplicated or absurd producer timestamp must not disable
        // interpolation: fall back to the wall clock for the interval.
        if (deltaNs == 0 || deltaNs > 500000000ull) deltaNs = hostDelta;
    }
    const uint64_t vsyncNs = static_cast<uint64_t>(1e9 / refreshHz_);
    // Every refusal is counted so the UI can name it instead of guessing. A 30 -> 30
    // session is only diagnosable from the device if the app can say WHICH condition
    // blocked the insert.
    const bool fgOff      = !fgReady_;
    const bool firstFrame = !prevValid_;
    const bool noSpareImg = swapImages_.size() < 3;
    // A 30 fps source on a 60 Hz panel must never be refused. The old gate demanded
    // 1.8 x vsync (30 ms), so ordinary 30 fps jitter (a 28 ms delta) read as "already
    // at panel rate" and the insert was skipped. 1.25 x vsync still refuses a source
    // that genuinely runs at the panel rate while accepting every 2x-rate source.
    const bool noInterval = deltaNs < (vsyncNs * 5) / 4;
    const bool canInsert  = !fgOff && !firstFrame && !noSpareImg && !noInterval;
    if (fgOff)           ++stFgOff_;
    else if (firstFrame) ++stFirst_;
    else if (noSpareImg) ++stNoSpare_;
    else if (noInterval) ++stNoInterval_;
    stDeltaMs_  = static_cast<int32_t>(deltaNs / 1000000ull);
    stVsyncMs_  = static_cast<int32_t>(vsyncNs / 1000000ull);
    if (noInterval && stDeltaMs_ > 0) stDeltaMs_ = -stDeltaMs_;

    // Flush a generated frame that was held back for the next vsync BEFORE acquiring
    // a new spare: its present waits on renderSpare_, and that semaphore must not be
    // re-signalled by the submit below while a present is still pending on it.
    presentPending();

    uint32_t spareIndex = 0;
    bool haveSpare = false;
    if (canInsert) {
        VkResult sr = vkAcquireNextImageKHR(device_, swapchain_, 250000000ull,
                                            acquireSpare_, VK_NULL_HANDLE, &spareIndex);
        if (sr == VK_ERROR_SURFACE_LOST_KHR || sr == VK_ERROR_DEVICE_LOST) {
            destroyAhb(ahb);
            dead_ = true;
            LOGE("swapchain lost on spare acquire: %d", (int)sr);
            return false;
        }
        const bool acquired = sr == VK_SUCCESS || sr == VK_SUBOPTIMAL_KHR;
        haveSpare = acquired && spareIndex != realIndex && spareIndex < swapImages_.size();
        if (!haveSpare) ++stSpareFail_;
        if (acquired && !haveSpare && spareIndex < swapImages_.size()) {
            // Hand an unusable acquired image straight back: an image left acquired
            // starves the swapchain, and its signalled acquire semaphore must be
            // consumed before it can be used as a submit wait again (undefined
            // behaviour that can wedge the Adreno driver).
            VkPresentInfoKHR psi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            psi.waitSemaphoreCount = 1;
            psi.pWaitSemaphores = &acquireSpare_;
            psi.swapchainCount = 1;
            psi.pSwapchains = &swapchain_;
            psi.pImageIndices = &spareIndex;
            vkQueuePresentKHR(queue_, &psi);
        }
    }

    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd_, &bi);

    const uint32_t srcQueue = foreignQueue_ ? VK_QUEUE_FAMILY_FOREIGN_EXT
                                            : VK_QUEUE_FAMILY_EXTERNAL_KHR;
    barrier(cmd_, ahb.image, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 0, VK_ACCESS_TRANSFER_READ_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            srcQueue, queueFamily_);

    barrier(cmd_, curr_.image, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    blit(cmd_, ahb.image, {static_cast<uint32_t>(w), static_cast<uint32_t>(h)},
         curr_.image, extent_, VK_FILTER_LINEAR);
    barrier(cmd_, curr_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    const VkImage realImage = swapImages_[realIndex];
    const VkImage spareImage = haveSpare ? swapImages_[spareIndex] : VK_NULL_HANDLE;

    if (fgReady_ && prevValid_ && haveSpare) {
        stage("frame record");
        barrier(cmd_, generated_.image, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // alpha = 0.5 places the generated frame at the temporal MIDPOINT between the
        // two captured frames. 0.35 put it closer to the older frame, leaving gaps of
        // 0.35 and 0.65 of a frame interval — uneven cadence for no benefit, since the
        // insert path already paces the pair around the midpoint.
        fg_.record(cmd_, prev_.view, curr_.view, generated_.view, 0.5f, 0);
        barrier(cmd_, generated_.image, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);

        barrier(cmd_, curr_.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(cmd_, prev_.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkImageCopy copy{};
        copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.dstSubresource = copy.srcSubresource;
        copy.extent = {extent_.width, extent_.height, 1};
        vkCmdCopyImage(cmd_, curr_.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       prev_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        barrier(cmd_, prev_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        barrier(cmd_, realImage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        blit(cmd_, curr_.image, extent_, realImage, extent_, VK_FILTER_NEAREST);
        barrier(cmd_, realImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

        barrier(cmd_, spareImage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        blit(cmd_, generated_.image, extent_, spareImage, extent_, VK_FILTER_NEAREST);
        barrier(cmd_, spareImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    } else {
        barrier(cmd_, realImage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        blit(cmd_, curr_.image, extent_, realImage, extent_, VK_FILTER_NEAREST);
        barrier(cmd_, realImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

        barrier(cmd_, curr_.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(cmd_, prev_.image, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkImageCopy copy{};
        copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.dstSubresource = copy.srcSubresource;
        copy.extent = {extent_.width, extent_.height, 1};
        vkCmdCopyImage(cmd_, curr_.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       prev_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        barrier(cmd_, prev_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }
    stage("frame submit");
    vkEndCommandBuffer(cmd_);

    VkSemaphore waits[2] = {acquireReal_, acquireSpare_};
    VkPipelineStageFlags stages[2] = {VK_PIPELINE_STAGE_TRANSFER_BIT,
                                      VK_PIPELINE_STAGE_TRANSFER_BIT};
    VkSemaphore signals[2] = {renderReal_, renderSpare_};
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = haveSpare ? 2u : 1u;
    si.pWaitSemaphores = waits;
    si.pWaitDstStageMask = stages;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd_;
    si.signalSemaphoreCount = haveSpare ? 2u : 1u;
    si.pSignalSemaphores = signals;
    if (vkQueueSubmit(queue_, 1, &si, fence_) != VK_SUCCESS) {
        LOGE("overlay submit failed");
        destroyAhb(ahb);
        return false;
    }
    fenceInFlight_ = true;

    // ── Even cadence ────────────────────────────────────────────────────────
    // Presenting the generated frame and the real frame back-to-back hands the
    // compositor two presents inside one vsync interval; one of them is coalesced
    // away, so the panel still shows a single new image per capture and the result
    // looks exactly like the source rate even though both presents were counted.
    // The generated midpoint is therefore held and presented at the next vsync by
    // presentPending() — that is what turns 30 source frames into 60 seen frames.
    if (haveSpare) {
        // The generated image is the midpoint of the last two captures, so it must
        // be shown BEFORE the frame that was just captured; holding it and
        // presenting it afterwards plays motion backwards. Present it now, and hold
        // the real frame for the next refresh instead.
        VkPresentInfoKHR psi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        psi.waitSemaphoreCount = 1;
        psi.pWaitSemaphores = &renderSpare_;
        psi.swapchainCount = 1;
        psi.pSwapchains = &swapchain_;
        psi.pImageIndices = &spareIndex;
        VkResult prg = vkQueuePresentKHR(queue_, &psi);
        if (prg != VK_SUCCESS) LOGW("present generated failed: %d", (int)prg);
    }
    VkResult pr = VK_SUCCESS;
    if (haveSpare) {
        // Hold the real frame; the vsync callback will present it on the next
        // refresh, one interval after the generated midpoint above.
        std::lock_guard<std::mutex> lk(pendingMutex_);
        pendingIndex_ = realIndex;
        pendingSem_ = renderReal_;
        pendingValid_ = true;
    } else {
        VkPresentInfoKHR pri{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pri.waitSemaphoreCount = 1;
        pri.pWaitSemaphores = &renderReal_;
        pri.swapchainCount = 1;
        pri.pSwapchains = &swapchain_;
        pri.pImageIndices = &realIndex;
        stage("frame present");
        pr = vkQueuePresentKHR(queue_, &pri);
    }
    if (pr == VK_ERROR_SURFACE_LOST_KHR || pr == VK_ERROR_DEVICE_LOST) {
        dead_ = true;
        LOGE("swapchain lost on present: %d", (int)pr);
    } else if (pr != VK_SUCCESS) {
        LOGW("present real failed: %d", (int)pr);
    }

    waitFence();
    destroyAhb(ahb);
    prevValid_ = true;
    stage("idle");
    ++presented_;
    if (haveSpare) ++stGenerated_; else ++stPass_;
    // Report how many frames actually reached the swapchain for THIS capture. The
    // Java side used to count one per submit, so the displayed output rate could
    // never exceed the capture rate no matter how well insertion worked.
    lastPresents_ = haveSpare ? 2 : 1;
    lastReason_   = haveSpare ? 0 : (fgOff ? 1 : firstFrame ? 2 : noSpareImg ? 3
                                 : noInterval ? 4 : 5);
    if ((presented_ & 0x3f) == 0) {
        LOGI("frame %llu %dx%d%s", (unsigned long long)timestampNs, w, h,
             haveSpare ? " 2x" : " passthrough");
    }
    return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
}

void Renderer::destroy() {
    stage("renderer destroy");
    if (device_) vkDeviceWaitIdle(device_);
    if (fgReady_) {
        fg_.destroy();
        fgReady_ = false;
    }
    destroyOffscreen();
    destroySwapchain();
    for (VkSemaphore* s : {&acquireReal_, &acquireSpare_, &renderReal_, &renderSpare_}) {
        if (*s) {
            vkDestroySemaphore(device_, *s, nullptr);
            *s = VK_NULL_HANDLE;
        }
    }
    if (fence_) {
        vkDestroyFence(device_, fence_, nullptr);
        fence_ = VK_NULL_HANDLE;
    }
    if (cmdPool_) {
        vkDestroyCommandPool(device_, cmdPool_, nullptr);
        cmdPool_ = VK_NULL_HANDLE;
    }
    cmd_ = VK_NULL_HANDLE;
    if (device_) {
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    if (surface_) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    if (instance_) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativeCreate(
        JNIEnv* env, jclass, jobject surface, jint width, jint height,
        jfloat refreshHz, jboolean frameGen, jstring crashDir,
        jint model, jint multiplier, jint perfPreset, jfloat flowScale,
        jint gmMode, jint frMode, jint frIters, jfloat frLambda, jfloat frDt,
        jfloat frEdge, jfloat frEps, jint extraImages, jboolean pacing, jint synthMode) {
    if (surface == nullptr || width <= 0 || height <= 0) return 0;
    if (crashDir != nullptr) {
        const char* dir = env->GetStringUTFChars(crashDir, nullptr);
        if (dir != nullptr) {
            installSignalHandlers(dir);
            env->ReleaseStringUTFChars(crashDir, dir);
        }
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("ANativeWindow_fromSurface failed");
        return 0;
    }
    winfg::Config cfg;
    cfg.enabled = true;
    // Model 3 (symmetric flow) reports confidence 1.0 for every pixel, which turns
    // off the occlusion gate downstream: on a bad match the frame is warped and
    // blended anyway, which is exactly the "melting / watery" look. The overlay
    // always uses model 4 (bidirectional flow with real per-pixel confidence); the
    // stored preference still selects the flow solver elsewhere (layer path).
    (void)model;
    cfg.model = 4;
    // The overlay presents one generated frame per capture, so 2x is the
    // truthful setting here. The layer path still uses 3x/4x multipliers.
    cfg.multiplier = 2;
    cfg.perfPreset = perfPreset;
    cfg.flowScale = flowScale;
    cfg.gmMode = gmMode;
    cfg.frMode = frMode;
    cfg.frIters = frIters;
    cfg.frLambda = frLambda;
    cfg.frDt = frDt;
    cfg.frEdge = frEdge;
    cfg.frEps = frEps;
    cfg.extraImages = extraImages;
    cfg.pacing = pacing;
    cfg.synthMode = synthMode;   // 0 = importance blend, 1 = occlusion-adaptive reprojection
    // ── OVERLAY COST CEILING ─────────────────────────────────────────────────
    // The overlay has one interval per captured frame and must fit the whole
    // solve inside it. With the balanced preset the solve overran a 16 ms budget,
    // so every capture cost two vsyncs: a 30 fps source produced ~15 real presents
    // and ~30 presented frames in total (the "halved, then scaled back" symptom).
    // The overlay therefore always runs the cheapest flow level, caps the TV-L1
    // iterations, and keeps the cheap reprojection synthesis. The Winlator layer
    // path is untouched and still honours the full profile.
    cfg.perfPreset = 2;                 // flow solved at 1/8 resolution
    if (cfg.frIters > 2) cfg.frIters = 2;
    // C1 global-motion prewarp stays ON. It was disabled here to buy frame budget,
    // but GameSir's own write-up of GSFG 1.1 names fast camera turns and large
    // character motion as the hardest cases — and camera motion is exactly what C1
    // removes before the dense search runs. Without it the solver spends its whole
    // pyramid on a global pan and returns nothing usable for the objects, which is
    // the ghosting that reads as "still 30".
    if (cfg.gmMode == 2) cfg.gmMode = 0;   // 0 = auto (engage when the affine is stable)
    cfg.model = 4;
    cfg.synthMode = 1;
    cfg.sanitize();

    Renderer* renderer = new Renderer();
    if (!renderer->init(window, width, height, refreshHz, frameGen == JNI_TRUE, cfg)) {
        renderer->destroy();  // owns and releases window_
        delete renderer;
        return 0;
    }
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativeSubmit(
        JNIEnv* env, jclass, jlong handle, jobject buffer, jlong timestampNs) {
    if (handle == 0 || buffer == nullptr) return 0;
    // AHardwareBuffer_fromHardwareBuffer() does NOT acquire a reference: the buffer it
    // returns is owned by the Java HardwareBuffer (android/hardware_buffer_jni.h).
    // Releasing it here stole that reference, so the ImageReader's buffer was freed while
    // Java still held it; the next Image.close()/finalizer then released a dead RefBase
    // and the process died with SIGSEGV inside libutils on the FinalizerDaemon, with no
    // renderer work in flight (stage: idle). Use the pointer for this call only.
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb) return 0;
    Renderer* renderer = reinterpret_cast<Renderer*>(handle);
    const bool presented = renderer->submit(ahb, static_cast<uint64_t>(timestampNs));
    if (!presented && renderer->dead()) return -1;
    // 2 when a generated frame was also presented, 1 for a passthrough present.
    return presented ? renderer->lastPresents() : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativePresentPending(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<Renderer*>(handle)->presentPending();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativeStats(
        JNIEnv* env, jclass, jlong handle, jintArray out) {
    if (handle == 0 || out == nullptr) return;
    Renderer* renderer = reinterpret_cast<Renderer*>(handle);
    int32_t v[10] = {0};
    renderer->readStats(v, 10);
    env->SetIntArrayRegion(out, 0, 10, reinterpret_cast<const jint*>(v));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    Renderer* renderer = reinterpret_cast<Renderer*>(handle);
    renderer->destroy();
    delete renderer;
}
