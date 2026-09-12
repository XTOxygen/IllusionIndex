#version 120
// IllusionIndex - textured quad shader with tint
uniform sampler2D u_tex;
uniform vec4      u_tint;

void main()
{
    gl_FragColor = texture2D(u_tex, gl_TexCoord[0].xy) * u_tint;
}
