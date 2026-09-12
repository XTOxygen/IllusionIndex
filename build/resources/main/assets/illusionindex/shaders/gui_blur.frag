#version 120
// IllusionIndex - separable Gaussian blur (9 taps), used for the blurred
// semi-transparent GUI background. Feed horizontal and vertical passes.
uniform sampler2D u_tex;
uniform vec2      u_texel;    // 1 / source texture size
uniform vec2      u_dir;      // (1,0) or (0,1)
uniform float     u_radius;   // blur radius multiplier (>= 1)

void main()
{
    vec2 uv = gl_TexCoord[0].xy;
    vec2 off = u_dir * u_texel * u_radius;

    vec3 sum = texture2D(u_tex, uv).rgb * 0.2270270270;
    sum += texture2D(u_tex, uv + off).rgb * 0.1945945946;
    sum += texture2D(u_tex, uv - off).rgb * 0.1945945946;
    sum += texture2D(u_tex, uv + off * 2.0).rgb * 0.1216216216;
    sum += texture2D(u_tex, uv - off * 2.0).rgb * 0.1216216216;
    sum += texture2D(u_tex, uv + off * 3.0).rgb * 0.0540540541;
    sum += texture2D(u_tex, uv - off * 3.0).rgb * 0.0540540541;
    sum += texture2D(u_tex, uv + off * 4.0).rgb * 0.0162162162;
    sum += texture2D(u_tex, uv - off * 4.0).rgb * 0.0162162162;

    gl_FragColor = vec4(sum, 1.0);
}
