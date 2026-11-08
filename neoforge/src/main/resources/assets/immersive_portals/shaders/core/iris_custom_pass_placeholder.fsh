#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Iris CustomPass uses its own actual GL program. This shader only describes
// a valid managed placeholder with no managed samplers and matching attributes.
void main() {
    fragColor = vec4(texCoord, 0.0, 1.0);
}
