#version 450
layout(binding = 0) uniform sampler2D texSampler;
layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int swapRB;
} pc;
layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;
void main() {
    vec4 c = texture(texSampler, fragTexCoord);
    outColor = (pc.swapRB != 0) ? c.bgra : c;
}
