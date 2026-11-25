#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <unistd.h>
#include <string.h>

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define HAL_PIXEL_FORMAT_BGRA_8888 5

AHardwareBuffer* createHardwareBuffer(int width, int height) {
    AHardwareBuffer_Desc buffDesc = {};
    buffDesc.width = width;
    buffDesc.height = height;
    buffDesc.layers = 1;
    buffDesc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    buffDesc.format = HAL_PIXEL_FORMAT_BGRA_8888;

    AHardwareBuffer *hardwareBuffer = NULL;
    if (AHardwareBuffer_allocate(&buffDesc, &hardwareBuffer) != 0) {
        printf("Failed to allocate AHardwareBuffer\n");
        return NULL;
    }

    return hardwareBuffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_GPUImage_createHardwareBuffer(JNIEnv *env, jclass obj, jshort width, jshort height) {
    AHardwareBuffer *buffer = createHardwareBuffer(width, height);
    if (!buffer) {
        printf("Failed to create hardware buffer\n");
        return 0;
    }
    return (jlong)buffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (hardwareBuffer) {
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        AHardwareBuffer_release(hardwareBuffer);
    }
}

JNIEXPORT jobject JNICALL
Java_com_winlator_cmod_renderer_GPUImage_lockHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) {
        printf("Invalid AHardwareBuffer pointer\n");
        return NULL;
    }
    
    void *virtualAddr;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &virtualAddr) != 0) {
        printf("Failed to lock AHardwareBuffer\n");
        return NULL;
    }

    AHardwareBuffer_Desc buffDesc;
    AHardwareBuffer_describe(hardwareBuffer, &buffDesc);

    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        printf("Failed to get Java class reference\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }

    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride == NULL) {
        printf("Failed to get setStride method ID\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }
    (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);

    jlong size = buffDesc.stride * buffDesc.height * 4;
    jobject buffer = (*env)->NewDirectByteBuffer(env, virtualAddr, size);
    if (buffer == NULL) {
        printf("Failed to create Java ByteBuffer\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
    }

    return buffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_GPUImage_nativeHardwareBufferFromSocket(JNIEnv *env, jclass obj, jint fd) {
    AHardwareBuffer *ahb;
    uint8_t buf = 1;
    
    if ((write(fd, &buf, 1)) == -1) {
        printf("Failed to write data to socketpair");
        return 0;
    }
    
    if ((AHardwareBuffer_recvHandleFromUnixSocket(fd, &ahb)) != 0) {
        printf("Failed to extract hardware buffer from socketpair");
        return 0;
    }
    
    AHardwareBuffer_Desc buffDesc;
    AHardwareBuffer_describe(ahb, &buffDesc);
    
    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        printf("Failed to get Java class reference\n");
        return 0;
    }

    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride == NULL) {
        printf("Failed to get setStride method ID\n");
        return 0;
    }
    (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);
    
    jfieldID format = (*env)->GetFieldID(env, cls, "format", "I");
    (*env)->SetIntField(env, obj, format, buffDesc.format);
    
    return (jlong)ahb;
}

// ---------------------------------------------------------------------------
// Old Display renderer: impor AHardwareBuffer sebagai tekstur GL (EGLImage).
// Dipanggil dari OldTexture di GL thread yang sedang memegang EGL context.
// ---------------------------------------------------------------------------
static EGLImageKHR oldDisplayCreateImage(AHardwareBuffer* hardwareBuffer, int textureId) {
    if (!hardwareBuffer) {
        printf("oldDisplayCreateImage: Invalid AHardwareBuffer pointer");
        return NULL;
    }

    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) {
        printf("oldDisplayCreateImage: Failed to get native client buffer");
        return NULL;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        printf("oldDisplayCreateImage: Invalid EGLDisplay");
        return NULL;
    }

    EGLImageKHR imageKHR = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!imageKHR) {
        printf("oldDisplayCreateImage: Failed to create EGLImageKHR");
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, textureId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES)imageKHR);
    GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    if (error != GL_NO_ERROR) {
        printf("oldDisplayCreateImage: Failed to bind EGLImage to texture (0x%x)", error);
        eglDestroyImageKHR(eglDisplay, imageKHR);
        return NULL;
    }

    return imageKHR;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_old_OldTexture_nativeCreateImage(JNIEnv *env, jclass obj, jlong hardwareBufferPtr, jint textureId) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) return 0;
    return (jlong)oldDisplayCreateImage(hardwareBuffer, textureId);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_old_OldTexture_nativeDestroyImage(JNIEnv *env, jclass obj, jlong imageKHRPtr) {
    EGLImageKHR imageKHR = (EGLImageKHR)imageKHRPtr;
    if (imageKHR) {
        EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglDestroyImageKHR(eglDisplay, imageKHR);
    }
}
