#version 120
// IllusionIndex - anti-aliased rounded-rectangle shader (fills, borders, pills, knobs)
//
// Draws one quad over the target rectangle; every pixel is classified against
// a rounded-box signed distance field, so corners get clean anti-aliasing via
// smoothstep (1px-ish transition expressed in supersampled pixels).
uniform vec2  u_vp;         // viewport size in pixels
uniform float u_ss;         // supersample factor (logical px = gl_FragCoord / ss)
uniform vec4  u_rect;       // target rectangle in logical pixels: x, y, w, h (y down)
uniform float u_radius;     // corner radius in logical pixels
uniform vec4  u_fill;       // fill colour (rgba, alpha 0 = no fill)
uniform vec4  u_border;     // border colour; alpha <= 0 disables the border
uniform float u_borderWidth;// border width in logical pixels

vec4 over(vec4 base, vec4 top)
{
    float a = top.a + base.a * (1.0 - top.a);
    if (a < 0.0001) return vec4(0.0, 0.0, 0.0, 0.0);
    return vec4((top.rgb * top.a + base.rgb * base.a * (1.0 - top.a)) / a, a);
}

// signed distance of a rounded box centred on the origin
float sdRoundBox(vec2 p, vec2 b, float r)
{
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}

void main()
{
    vec2 p = vec2(gl_FragCoord.x, u_vp.y - gl_FragCoord.y) / u_ss; // logical, y-down
    vec2 halfSize = u_rect.zw * 0.5;
    float r = min(u_radius, min(halfSize.x, halfSize.y));
    vec2 center = u_rect.xy + halfSize;
    // sdRoundBox takes the box half extents (it applies the corner radius itself)
    float d = sdRoundBox(p - center, halfSize, r);

    float aa = max(1.2 / u_ss, fwidth(d));
    float cov = 1.0 - smoothstep(-aa, aa, d);
    if (cov <= 0.002) discard;

    vec4 col = vec4(u_fill.rgb, u_fill.a * cov);
    if (u_border.a > 0.001)
    {
        float bd = abs(d) - u_borderWidth * 0.5;
        float bm = 1.0 - smoothstep(-aa, aa, bd);
        vec4 ring = vec4(u_border.rgb, u_border.a * bm * cov);
        col = over(col, ring);
    }
    gl_FragColor = col;
}
