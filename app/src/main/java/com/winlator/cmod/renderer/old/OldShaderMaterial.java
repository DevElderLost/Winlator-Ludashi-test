package com.winlator.cmod.renderer.old;

import android.opengl.GLES20;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/** Port dari ShaderMaterial + WindowMaterial + CursorMaterial experimental-v2. */
public class OldShaderMaterial {
    public int programId;
    private final Map<String, Integer> uniforms = new LinkedHashMap<>();

    protected void setUniformNames(String... names) {
        uniforms.clear();
        for (String name : names) uniforms.put(name, -1);
    }

    private static int compileShaders(String vertexShader, String fragmentShader) {
        int beginIndex = vertexShader.indexOf("void main() {");
        vertexShader = vertexShader.substring(0, beginIndex) +
                "vec2 applyXForm(vec2 p, float xform[6]) {\n" +
                "return vec2(xform[0] * p.x + xform[2] * p.y + xform[4], xform[1] * p.x + xform[3] * p.y + xform[5]);\n" +
                "}\n" +
                vertexShader.substring(beginIndex);

        int programId = GLES20.glCreateProgram();
        int[] compiled = new int[1];

        int vertexShaderId = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShaderId, vertexShader);
        GLES20.glCompileShader(vertexShaderId);
        GLES20.glGetShaderiv(vertexShaderId, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            throw new RuntimeException("Could not compile vertex shader: \n" + GLES20.glGetShaderInfoLog(vertexShaderId));
        }
        GLES20.glAttachShader(programId, vertexShaderId);

        int fragmentShaderId = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShaderId, fragmentShader);
        GLES20.glCompileShader(fragmentShaderId);
        GLES20.glGetShaderiv(fragmentShaderId, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            throw new RuntimeException("Could not compile fragment shader: \n" + GLES20.glGetShaderInfoLog(fragmentShaderId));
        }
        GLES20.glAttachShader(programId, fragmentShaderId);

        GLES20.glLinkProgram(programId);

        GLES20.glDeleteShader(vertexShaderId);
        GLES20.glDeleteShader(fragmentShaderId);
        return programId;
    }

    protected String getVertexShader() {
        return "";
    }

    protected String getFragmentShader() {
        return "";
    }

    public void use() {
        if (programId == 0) programId = compileShaders(getVertexShader(), getFragmentShader());
        GLES20.glUseProgram(programId);

        for (Map.Entry<String, Integer> entry : uniforms.entrySet()) {
            if (entry.getValue() == -1) {
                entry.setValue(GLES20.glGetUniformLocation(programId, entry.getKey()));
            }
        }
    }

    public int getUniformLocation(String name) {
        Integer location = uniforms.get(name);
        if (location == null) {
            Log.e("OldShaderMaterial", "Uniform " + name + " is not registered in setUniformNames().");
            return -1;
        }
        return location;
    }

    public void destroy() {
        if (programId != 0) GLES20.glDeleteProgram(programId);
        programId = 0;
        for (Map.Entry<String, Integer> entry : uniforms.entrySet()) entry.setValue(-1);
    }

    private static final String QUAD_VERTEX_SHADER =
            "uniform float xform[6];\n" +
            "uniform vec2 viewSize;\n" +
            "attribute vec2 position;\n" +
            "varying vec2 vUV;\n" +

            "void main() {\n" +
                "vUV = position;\n" +
                "vec2 transformedPos = applyXForm(position, xform);\n" +
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);\n" +
            "}";

    public static class WindowMaterial extends OldShaderMaterial {
        public WindowMaterial() {
            setUniformNames("xform", "viewSize", "texture");
        }

        @Override
        protected String getVertexShader() {
            return QUAD_VERTEX_SHADER;
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision mediump float;\n" +
                "uniform sampler2D texture;\n" +
                "varying vec2 vUV;\n" +

                "void main() {\n" +
                    "gl_FragColor = vec4(texture2D(texture, vUV).rgb, 1.0);\n" +
                "}";
        }
    }

    /** Pass akhir Soft Stretch: kurva horizontal yang sama dengan cpp/winlator/renderer/vulkan/window_stretch.frag. */
    public static class SoftStretchMaterial extends OldShaderMaterial {
        public SoftStretchMaterial() {
            setUniformNames("rect", "texture", "strength", "profile");
        }

        @Override
        protected String getVertexShader() {
            return
                "uniform vec4 rect;\n" +
                "attribute vec2 position;\n" +
                "varying vec2 vUV;\n" +

                "void main() {\n" +
                    // position (0,0) = kiri-atas; tekstur FBO menyimpan baris atas di v = 1
                    "vUV = vec2(position.x, 1.0 - position.y);\n" +
                    "gl_Position = vec4(mix(rect.x, rect.z, position.x), mix(rect.y, rect.w, position.y), 0.0, 1.0);\n" +
                "}";
        }

        @Override
        protected String getFragmentShader() {
            return
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n" +
                "uniform sampler2D texture;\n" +
                "uniform float strength;\n" +
                "uniform float profile;\n" +
                "varying vec2 vUV;\n" +

                "void main() {\n" +
                    "float x = vUV.x * 2.0 - 1.0;\n" +
                    "float sinShape = sin(1.57079632679 * x);\n" +
                    "float x2 = x * x;\n" +
                    "float x5 = x2 * x2 * x;\n" +
                    "float quintic = 0.5 * x5 - 1.5 * x2 * x + 2.0 * x;\n" +
                    "float warped = mix(x, mix(sinShape, quintic, profile), strength);\n" +
                    "float u = clamp(0.5 * warped + 0.5, 0.0, 1.0);\n" +
                    "gl_FragColor = vec4(texture2D(texture, vec2(u, vUV.y)).rgb, 1.0);\n" +
                "}";
        }
    }

    public static class CursorMaterial extends OldShaderMaterial {
        public CursorMaterial() {
            setUniformNames("xform", "viewSize", "texture");
        }

        @Override
        protected String getVertexShader() {
            return QUAD_VERTEX_SHADER;
        }

        @Override
        protected String getFragmentShader() {
            return
                "precision mediump float;\n" +
                "uniform sampler2D texture;\n" +
                "varying vec2 vUV;\n" +

                "void main() {\n" +
                    "gl_FragColor = texture2D(texture, vUV);\n" +
                "}";
        }
    }
}
