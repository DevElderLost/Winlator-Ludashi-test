#include "VulkanRendererContext.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <android/api-level.h>
#include <android/log.h>
typedef void* (*pfn_SCCreateFromWindow)(ANativeWindow*, const char*);
typedef void  (*pfn_SCRelease)(void*);
typedef void* (*pfn_STCreate)();
typedef void  (*pfn_STDelete)(void*);
typedef void  (*pfn_STApply)(void*);
typedef void  (*pfn_STSetBuffer)(void*, void*, AHardwareBuffer*, int);
typedef void  (*pfn_STSetZOrder)(void*, void*, int32_t);
typedef void  (*pfn_STSetVisibility)(void*, void*, int8_t);
typedef void  (*pfn_STSetGeometry)(void*, void*, const ARect*, const ARect*, int32_t);
typedef void  (*pfn_STSetBackPressure)(void*, void*, bool);

bool VulkanRendererContext::loadScanoutApi() {
    if (scanoutApiLoaded) return fnSCCreateFromWin != nullptr;
    scanoutApiLoaded = true;
    int apiLevel = android_get_device_api_level();
    if (apiLevel < 29) { SCANOUT_LOG("loadScanoutApi: API < 29, unavailable"); return false; }

    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) { SCANOUT_LOG("loadScanoutApi: dlopen libandroid.so failed: %s", dlerror()); return false; }

    fnSCCreateFromWin = dlsym(lib, "ASurfaceControl_createFromWindow");
    fnSCRelease       = dlsym(lib, "ASurfaceControl_release");
    fnSTCreate        = dlsym(lib, "ASurfaceTransaction_create");
    fnSTDelete        = dlsym(lib, "ASurfaceTransaction_delete");
    fnSTApply         = dlsym(lib, "ASurfaceTransaction_apply");
    fnSTSetBuffer     = dlsym(lib, "ASurfaceTransaction_setBuffer");
    fnSTSetZOrder     = dlsym(lib, "ASurfaceTransaction_setZOrder");
    fnSTSetVisibility = dlsym(lib, "ASurfaceTransaction_setVisibility");
    fnSTSetGeometry      = dlsym(lib, "ASurfaceTransaction_setGeometry");
    fnSTSetBackPressure  = dlsym(lib, "ASurfaceTransaction_setEnableBackPressure");

    bool coreOk = fnSCCreateFromWin && fnSCRelease &&
                  fnSTCreate && fnSTDelete && fnSTApply &&
                  fnSTSetBuffer && fnSTSetVisibility && fnSTSetGeometry;
    if (!coreOk) {
        SCANOUT_LOG("loadScanoutApi: core symbols missing, scanout disabled");
        fnSCCreateFromWin = fnSCRelease = fnSTCreate = fnSTDelete = fnSTApply =
        fnSTSetBuffer = fnSTSetZOrder = fnSTSetVisibility = fnSTSetGeometry = nullptr;
        return false;
    }
    if (!fnSTSetZOrder) SCANOUT_LOG("loadScanoutApi: setZOrder unavailable (non-critical)");
    const char* gpuBlitEnv = std::getenv("WINLATOR_SCANOUT_GPU_BLIT");
    scanoutEnvGpuBlit = (!gpuBlitEnv || gpuBlitEnv[0] == '1');
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    SCANOUT_LOG("loadScanoutApi: envGpuBlit=%d swapRB=%d alwaysGpuBlit=%d",
        (int)scanoutEnvGpuBlit, (int)swapRB, (int)scanoutAlwaysGpuBlit);
    SCANOUT_LOG("loadScanoutApi: core symbols OK, scanout enabled");
    return true;
}

#define SC_CREATE(win, name)     ((pfn_SCCreateFromWindow)fnSCCreateFromWin)((win), (name))
#define SC_RELEASE(sc)        ((pfn_SCRelease)fnSCRelease)((sc))
#define ST_CREATE()           ((pfn_STCreate)fnSTCreate)()
#define ST_DELETE(t)          ((pfn_STDelete)fnSTDelete)((t))
#define ST_APPLY(t)           ((pfn_STApply)fnSTApply)((t))
#define ST_SETBUF(t,sc,b,f)   ((pfn_STSetBuffer)fnSTSetBuffer)((t),(sc),(b),(f))
#define ST_SETZORDER(t,sc,z)  if(fnSTSetZOrder) ((pfn_STSetZOrder)fnSTSetZOrder)((t),(sc),(z))
#define ST_SETVIS(t,sc,v)     ((pfn_STSetVisibility)fnSTSetVisibility)((t),(sc),(v))
#define ST_SETGEO(t,sc,s,d,r)  ((pfn_STSetGeometry)fnSTSetGeometry)((t),(sc),(s),(d),(r))
#define ST_SETBP(t,sc,v)       if(fnSTSetBackPressure) ((pfn_STSetBackPressure)fnSTSetBackPressure)((t),(sc),(v))

void VulkanRendererContext::initScanout() {
    if (scanoutActive.load()) return;
    if (!window || !loadScanoutApi()) {
        SCANOUT_LOG("initScanout: loadApi failed, aborting");
        return;
    }

    scanoutGameSC   = SC_CREATE(window, "winlator_game");
    scanoutCursorSC = SC_CREATE(window, "winlator_cursor");
    if (!scanoutGameSC || !scanoutCursorSC) {
        SCANOUT_LOG("initScanout: SC creation failed, aborting");
        if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
        if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
        return;
    }

    void* t = ST_CREATE();
    ST_SETZORDER(t, scanoutGameSC, 0);
    ST_SETZORDER(t, scanoutCursorSC, 1);
    ST_SETVIS(t, scanoutGameSC,   0);
    ST_SETVIS(t, scanoutCursorSC, 0);
    ST_APPLY(t);
    ST_DELETE(t);

    gameScVisible = false; lastDstW = 0;
    gameFrameDelivered.store(false);
    scanoutActive.store(true);
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    scanoutNeedsGpuBlit = scanoutAlwaysGpuBlit;
    SCANOUT_LOG("initScanout: SUCCESS, scanout active");
}

void VulkanRendererContext::destroyScanout() {
    if (!scanoutActive.load()) return;
    scanoutActive.store(false);

    if (scanoutGameSC || scanoutCursorSC) {
        void* t = ST_CREATE();
        if (scanoutGameSC)   ST_SETVIS(t, scanoutGameSC,   0);
        if (scanoutCursorSC) ST_SETVIS(t, scanoutCursorSC, 0);
        ST_APPLY(t);
        ST_DELETE(t);
    }

    if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
    if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
    if (scanoutCursorBuf) {
        AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer*>(scanoutCursorBuf));
        scanoutCursorBuf=nullptr;
    }
    if (scanoutLocalImg != VK_NULL_HANDLE) {
        vk_.DestroyImage(device, scanoutLocalImg, nullptr);
        scanoutLocalImg = VK_NULL_HANDLE;
    }
    if (scanoutLocalMem != VK_NULL_HANDLE) {
        vk_.FreeMemory(device, scanoutLocalMem, nullptr);
        scanoutLocalMem = VK_NULL_HANDLE;
    }
    if (scanoutLocalAhb) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
    }
    scanoutLocalW = scanoutLocalH = 0;
    scanoutNeedsGpuBlit = false;
    scanoutCursorBufW = scanoutCursorBufH = 0;
}

void VulkanRendererContext::scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h) {
    if (!scanoutActive.load() || !scanoutGameSC || !ahb) {
        RLOG("scanoutSetBuffer: SKIPPED active=%d sc=%p ahb=%p",
            (int)scanoutActive.load(),scanoutGameSC,(void*)ahb);
        return;
    }
    static int _scanoutBufCnt=0;
    if (++_scanoutBufCnt == 1) {
        AHardwareBuffer_Desc d{};
        AHardwareBuffer_describe(ahb, &d);
        bool hasOverlay = (d.usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY) != 0;
        RLOG("FIRST FRAME: fmt=%u %ux%u usage=0x%llx COMPOSER_OVERLAY=%s",
            d.format, d.width, d.height, (unsigned long long)d.usage,
            hasOverlay ? "YES" : "NO");
        scanoutNeedsGpuBlit = !hasOverlay;
    }
    AHardwareBuffer_acquire(ahb);
    { std::lock_guard<std::mutex> lk(scanoutMutex);
      if (scanoutPendingDirty.load() && scanoutPending.ahb && scanoutPending.ahb != ahb) {
          AHardwareBuffer_release(scanoutPending.ahb);
      }
      scanoutPending = {ahb, x, y, w, h};
      scanoutPendingDirty.store(true, std::memory_order_release); }
    needsRender.store(true, std::memory_order_release);
    dirtyCV.notify_one();
}

void VulkanRendererContext::initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin) {
    if (scanoutActive.load()) destroyScanout();
    if (!loadScanoutApi()) {
        ANativeWindow_release(gameWin); ANativeWindow_release(cursorWin);
        initScanout(); return;
    }
    scanoutGameSC   = SC_CREATE(gameWin,   "winlator_game_buf");
    scanoutCursorSC = SC_CREATE(cursorWin, "winlator_cursor_buf");
    ANativeWindow_release(gameWin); ANativeWindow_release(cursorWin);
    if (!scanoutGameSC || !scanoutCursorSC) {
        SCANOUT_LOG("initScanoutFromWindows: SC creation failed, fallback to child path");
        if (scanoutGameSC)   { SC_RELEASE(scanoutGameSC);   scanoutGameSC=nullptr; }
        if (scanoutCursorSC) { SC_RELEASE(scanoutCursorSC); scanoutCursorSC=nullptr; }
        initScanout(); return;
    }
    void* t = ST_CREATE();
    ST_SETZORDER(t, scanoutGameSC, 0);
    ST_SETZORDER(t, scanoutCursorSC, 1);
    ST_SETVIS(t, scanoutGameSC,   0);
    ST_SETVIS(t, scanoutCursorSC, 0);
    ST_APPLY(t); ST_DELETE(t);
    gameScVisible = false; lastDstW = 0;
    gameFrameDelivered.store(false);
    scanoutActive.store(true);
    scanoutAlwaysGpuBlit = scanoutEnvGpuBlit || swapRB;
    scanoutNeedsGpuBlit = scanoutAlwaysGpuBlit;
    SCANOUT_LOG("initScanoutFromWindows: SUCCESS (sibling path via Java getSurfaceControl)");
}

bool VulkanRendererContext::ensureScanoutLocalAhb(int w, int h, uint32_t ahbFormat) {
    if (scanoutLocalAhb && scanoutLocalImg != VK_NULL_HANDLE &&
        scanoutLocalW == w && scanoutLocalH == h) {
        return true;
    }

    if (scanoutLocalImg != VK_NULL_HANDLE) {
        vk_.DeviceWaitIdle(device);
        vk_.DestroyImage(device, scanoutLocalImg, nullptr);
        scanoutLocalImg = VK_NULL_HANDLE;
    }
    if (scanoutLocalMem != VK_NULL_HANDLE) {
        vk_.FreeMemory(device, scanoutLocalMem, nullptr);
        scanoutLocalMem = VK_NULL_HANDLE;
    }
    if (scanoutLocalAhb) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
    }

    AHardwareBuffer_Desc d{};
    d.width = (uint32_t)w;
    d.height = (uint32_t)h;
    d.layers = 1;
    d.format = ahbFormat;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER
            | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
            | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    if (AHardwareBuffer_allocate(&d, &scanoutLocalAhb) != 0) return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext = &fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device, scanoutLocalAhb, &props) != VK_SUCCESS) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
        return false;
    }

    bool extFmt = (fmtP.format == VK_FORMAT_UNDEFINED);
    VkExternalFormatANDROID ef{};
    ef.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    ef.externalFormat = extFmt ? fmtP.externalFormat : 0;
    VkExternalMemoryImageCreateInfo emi{};
    emi.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    if (extFmt) {
        ef.pNext = const_cast<void*>(emi.pNext);
        emi.pNext = &ef;
    }

    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext = &emi;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = extFmt ? VK_FORMAT_UNDEFINED : fmtP.format;
    ii.extent = {(uint32_t)w, (uint32_t)h, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device, &ii, nullptr, &scanoutLocalImg) != VK_SUCCESS) {
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.buffer = scanoutLocalAhb;
    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.pNext = &imp;
    ded.image = scanoutLocalImg;
    VkMemoryAllocateInfo mai{};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext = &ded;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = findMemType(props.memoryTypeBits, 0);
    if (vk_.AllocateMemory(device, &mai, nullptr, &scanoutLocalMem) != VK_SUCCESS ||
        vk_.BindImageMemory(device, scanoutLocalImg, scanoutLocalMem, 0) != VK_SUCCESS) {
        if (scanoutLocalMem != VK_NULL_HANDLE) {
            vk_.FreeMemory(device, scanoutLocalMem, nullptr);
            scanoutLocalMem = VK_NULL_HANDLE;
        }
        vk_.DestroyImage(device, scanoutLocalImg, nullptr);
        scanoutLocalImg = VK_NULL_HANDLE;
        AHardwareBuffer_release(scanoutLocalAhb);
        scanoutLocalAhb = nullptr;
        return false;
    }

    scanoutLocalW = w;
    scanoutLocalH = h;
    return true;
}

void VulkanRendererContext::applyScanoutBuffer() {
    if (!scanoutPendingDirty.load(std::memory_order_acquire)) return;
    ScanoutPending p;
    { std::lock_guard<std::mutex> lk(scanoutMutex);
      if (!scanoutPendingDirty.load()) return;
      p = scanoutPending;
      scanoutPendingDirty.store(false, std::memory_order_relaxed); }
    AHardwareBuffer* ahb=p.ahb; int w=p.w, h=p.h; (void)p.x; (void)p.y;
    if (!ahb || !scanoutGameSC) return;

    int32_t cw = containerWidth  > 0 ? containerWidth  : w;
    int32_t ch = containerHeight > 0 ? containerHeight : h;
    ARect src{0, 0, cw, ch};
    ARect dst;
    if (scanoutDstW > 0 && scanoutDstH > 0) {
        dst = {scanoutDstX, scanoutDstY, scanoutDstX+scanoutDstW, scanoutDstY+scanoutDstH};
    } else {
        dst = {0, 0, cw, ch};
    }

    AHardwareBuffer* presentAhb = ahb;
    if (scanoutAlwaysGpuBlit || scanoutNeedsGpuBlit) {
        AHardwareBuffer_Desc srcDesc{};
        AHardwareBuffer_describe(ahb, &srcDesc);
        if (ensureScanoutLocalAhb((int)srcDesc.width, (int)srcDesc.height, srcDesc.format)) {
            VkImage srcImg = VK_NULL_HANDLE;
            auto it = ahbTexCache.find(ahb);
            if (it != ahbTexCache.end()) {
                srcImg = it->second.img;
            } else {
                WinTex tmp{};
                if (importAHBToWinTex(tmp, ahb)) {
                    AHBCached cached{tmp.img, tmp.mem, tmp.view, tmp.ds};
                    AHardwareBuffer_acquire(ahb);
                    ahbTexCache[ahb] = cached;
                    ahbCacheLRU.push_back(ahb);
                    srcImg = cached.img;
                }
            }

            if (srcImg != VK_NULL_HANDLE && scanoutLocalImg != VK_NULL_HANDLE) {
                VkCommandBuffer cb = beginOneTime();
                transition(cb, srcImg,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                transition(cb, scanoutLocalImg,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

                VkImageCopy region{};
                region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                region.extent = {(uint32_t)w, (uint32_t)h, 1};
                vk_.CmdCopyImage(cb,
                    srcImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    scanoutLocalImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    1, &region);
                endOneTime(cb);
                presentAhb = scanoutLocalAhb;
            }
        }
    }

    void* t=ST_CREATE();
    ST_SETBUF(t,scanoutGameSC,presentAhb,-1);
    ST_SETGEO(t,scanoutGameSC,&src,&dst,0);
    ST_SETVIS(t,scanoutGameSC,1);
    ST_SETBP(t,scanoutGameSC,false);
    ST_APPLY(t);
    gameFrameDelivered.store(true);
    ST_DELETE(t);
    AHardwareBuffer_release(ahb);
}

void VulkanRendererContext::scanoutSetDst(int x, int y, int w, int h) {
    scanoutDstX=x; scanoutDstY=y; scanoutDstW=w; scanoutDstH=h;
}

void VulkanRendererContext::scanoutSetCursorImage(void* pixels, short w, short h, short stride) {
    if (!scanoutActive.load() || !scanoutCursorSC || !pixels || w<=0 || h<=0) return;
    if (stride <= 0) stride = w;

    auto* curBuf = reinterpret_cast<AHardwareBuffer**>(&scanoutCursorBuf);
    if (*curBuf && (scanoutCursorBufW != w || scanoutCursorBufH != h)) {
        AHardwareBuffer_release(*curBuf);
        *curBuf = nullptr;
    }
    if (!*curBuf) {
        AHardwareBuffer_Desc d{};
        d.width  = (uint32_t)w; d.height = (uint32_t)h; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage  = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                   AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                   AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (AHardwareBuffer_allocate(&d, curBuf) != 0) return;
        scanoutCursorBufW = w; scanoutCursorBufH = h;
    }

    AHardwareBuffer_Desc dstDesc{};
    AHardwareBuffer_describe(*curBuf, &dstDesc);
    uint32_t dstStride = dstDesc.stride;

    void* dst = nullptr;
    if (AHardwareBuffer_lock(*curBuf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            -1, nullptr, &dst) != 0) return;
    const uint32_t* src = reinterpret_cast<const uint32_t*>(pixels);
    auto* dstPx = reinterpret_cast<uint32_t*>(dst);
    for (int row = 0; row < h; ++row) {
        memcpy(dstPx + (size_t)row * dstStride,
               src + (size_t)row * (uint32_t)stride,
               (size_t)w * 4);
    }
    AHardwareBuffer_unlock(*curBuf, nullptr);

    void* t = ST_CREATE();
    ST_SETBUF(t, scanoutCursorSC, *curBuf, -1);
    ST_APPLY(t);
    ST_DELETE(t);
}

void VulkanRendererContext::scanoutSetCursorPos(short x, short y, short hotX, short hotY) {
    if (!scanoutActive.load() || !scanoutCursorSC) return;
    if (scanoutCursorBufW <= 0 || scanoutCursorBufH <= 0) return;

    int32_t cw = containerWidth  > 0 ? containerWidth  : surfaceWidth;
    int32_t ch = containerHeight > 0 ? containerHeight : surfaceHeight;
    int32_t dx = scanoutDstW > 0 ? scanoutDstX : 0;
    int32_t dy = scanoutDstW > 0 ? scanoutDstY : 0;
    int32_t dw = scanoutDstW > 0 ? scanoutDstW : cw;
    int32_t dh = scanoutDstW > 0 ? scanoutDstH : ch;

    float fx = dx + ((float)x / cw) * dw;
    float fy = dy + ((float)y / ch) * dh;
    float scaleW = (float)dw / cw;
    float scaleH = (float)dh / ch;
    int32_t curW = (int32_t)(scanoutCursorBufW * scaleW);
    int32_t curH = (int32_t)(scanoutCursorBufH * scaleH);
    int32_t cx = std::max(0, (int32_t)(fx - hotX * scaleW));
    int32_t cy = std::max(0, (int32_t)(fy - hotY * scaleH));

    ARect src{0, 0, scanoutCursorBufW, scanoutCursorBufH};
    ARect dst{cx, cy, cx + curW, cy + curH};

    void* t = ST_CREATE();
    ST_SETGEO(t, scanoutCursorSC, &src, &dst, 0);
    ST_SETVIS(t, scanoutCursorSC, 1);
    ST_APPLY(t);
    ST_DELETE(t);
}
