#version 120
// IllusionIndex - generic fullscreen/quad vertex shader (fixed-function compatible)
void main()
{
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_TexCoord[0] = gl_MultiTexCoord0;
}
