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
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "win-fg-overlay"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

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
    bool init(ANativeWindow* window, int width, int height, const winfg::Config& cfg);
    void submit(AHardwareBuffer* buffer, uint64_t timestampNs);
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
    bool foreignQueue_ = false;

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
    uint32_t minImages = std::max(4u, caps.minImageCount);
    if (caps.maxImageCount != 0 && minImages > caps.maxImageCount) minImages = caps.maxImageCount;

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
    if (!createSwapchain()) return;
    fg_.onResize(extent_, VK_FORMAT_R8G8B8A8_UNORM, true);
    createOffscreen();
}

bool Renderer::init(ANativeWindow* window, int width, int height, const winfg::Config& cfg) {
    window_ = window;
    cfg_ = cfg;
    extent_ = {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
    if (!createInstance(window_)) return false;
    if (!pickDevice()) return false;
    if (!createDevice()) return false;

    if (!fg_.init(&dd_, &id_, physical_, device_, queueFamily_, queue_)) {
        LOGE("FrameGen init failed");
        return false;
    }
    fgReady_ = true;
    fg_.configure(cfg_);
    if (!createSwapchain()) return false;
    if (!fg_.onResize(extent_, VK_FORMAT_R8G8B8A8_UNORM)) return false;
    if (!createOffscreen()) return false;

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
    LOGI("overlay renderer ready (%dx%d, 2x insertion)", width, height);
    return true;
}

bool Renderer::importAhb(AHardwareBuffer* buffer, int width, int height, AhbImage& out) {
    if (!getAhbProps_ || width <= 0 || height <= 0) return false;

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

void Renderer::submit(AHardwareBuffer* buffer, uint64_t timestampNs) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!device_ || !buffer || swapchain_ == VK_NULL_HANDLE) return;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    int w = static_cast<int>(desc.width);
    int h = static_cast<int>(desc.height);
    if (w != static_cast<int>(extent_.width) || h != static_cast<int>(extent_.height)) {
        resize(w, h);
        if (swapchain_ == VK_NULL_HANDLE) return;
    }

    AhbImage ahb{};
    if (!importAhb(buffer, w, h, ahb)) return;

    waitFence();
    vkResetFences(device_, 1, &fence_);

    uint32_t realIndex = 0;
    VkResult ar = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                        acquireReal_, VK_NULL_HANDLE, &realIndex);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) {
        destroyAhb(ahb);
        int w0 = static_cast<int>(extent_.width);
        int h0 = static_cast<int>(extent_.height);
        extent_ = {0, 0};
        resize(w0, h0);
        return;
    }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        destroyAhb(ahb);
        LOGW("acquire real failed: %d", (int)ar);
        return;
    }

    uint32_t spareIndex = 0;
    bool haveSpare = false;
    VkResult sr = vkAcquireNextImageKHR(device_, swapchain_, 2000000ull,
                                        acquireSpare_, VK_NULL_HANDLE, &spareIndex);
    if ((sr == VK_SUCCESS || sr == VK_SUBOPTIMAL_KHR) && spareIndex != realIndex) {
        haveSpare = true;
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

    if (prevValid_ && haveSpare) {
        barrier(cmd_, generated_.image, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        fg_.record(cmd_, prev_.view, curr_.view, generated_.view, 0.35f, 0);
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
        return;
    }
    fenceInFlight_ = true;

    if (haveSpare) {
        VkPresentInfoKHR psi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        psi.waitSemaphoreCount = 1;
        psi.pWaitSemaphores = &renderSpare_;
        psi.swapchainCount = 1;
        psi.pSwapchains = &swapchain_;
        psi.pImageIndices = &spareIndex;
        VkResult pr = vkQueuePresentKHR(queue_, &psi);
        if (pr != VK_SUCCESS) LOGW("present generated failed: %d", (int)pr);
    }
    VkPresentInfoKHR pri{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pri.waitSemaphoreCount = 1;
    pri.pWaitSemaphores = &renderReal_;
    pri.swapchainCount = 1;
    pri.pSwapchains = &swapchain_;
    pri.pImageIndices = &realIndex;
    VkResult pr = vkQueuePresentKHR(queue_, &pri);
    if (pr != VK_SUCCESS) LOGW("present real failed: %d", (int)pr);

    waitFence();
    destroyAhb(ahb);
    prevValid_ = true;
    if ((timestampNs & 0x3f) == 0) {
        LOGI("frame %llu %dx%d%s", (unsigned long long)timestampNs, w, h,
             haveSpare ? " 2x" : " passthrough");
    }
}

void Renderer::destroy() {
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
        jint model, jint multiplier, jint perfPreset, jfloat flowScale,
        jint gmMode, jint frMode, jint frIters, jfloat frLambda, jfloat frDt,
        jfloat frEdge, jfloat frEps, jint extraImages, jboolean pacing) {
    if (surface == nullptr || width <= 0 || height <= 0) return 0;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("ANativeWindow_fromSurface failed");
        return 0;
    }
    winfg::Config cfg;
    cfg.enabled = true;
    cfg.model = model;
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
    cfg.sanitize();

    Renderer* renderer = new Renderer();
    if (!renderer->init(window, width, height, cfg)) {
        renderer->destroy();  // owns and releases window_
        delete renderer;
        return 0;
    }
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativeSubmit(
        JNIEnv* env, jclass, jlong handle, jobject buffer, jlong timestampNs) {
    if (handle == 0 || buffer == nullptr) return;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb) return;
    reinterpret_cast<Renderer*>(handle)->submit(ahb, static_cast<uint64_t>(timestampNs));
    AHardwareBuffer_release(ahb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winfg_launcher_OverlayRenderer_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    Renderer* renderer = reinterpret_cast<Renderer*>(handle);
    renderer->destroy();
    delete renderer;
}
