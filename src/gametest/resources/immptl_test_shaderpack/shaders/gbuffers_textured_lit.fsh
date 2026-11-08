#version 120

#define IMMPTL_DEPTH_REGRESSION 0 // [0 1]

uniform sampler2D lightmap;
uniform sampler2D texture;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

void main() {
    vec4 color = texture2D(texture, texcoord) * glcolor;
#if IMMPTL_DEPTH_REGRESSION == 1
    color = texture2D(texture, texcoord);
#else
    color *= texture2D(lightmap, lmcoord);
#endif

/* DRAWBUFFERS:0 */
    gl_FragData[0] = color;
}
