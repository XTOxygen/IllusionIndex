package com.endwithme.illusionindex.gui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Shader driven 2D rendering engine used by {@link GuiConfig}.
 *
 * <p>Everything the GUI draws (the blurred world backdrop, rounded card
 * backgrounds, white 1px borders, pill switches, sliders, colour swatches,
 * hex input field) is rasterised into an off-screen framebuffer at a
 * supersampled resolution and composited at the end - text and shapes are
 * anti-aliased by that supersampling. Rounded corners come from a GLSL signed
 * distance shader and the background blur from separable Gaussian passes.
 * No third-party libraries are used and the GLSL 1.20 / fixed-function path is
 * compatible with OptiFine (which targets the exact same pipeline).
 *
 * @author EndWithMe
 */
public final class UiRenderer
{
    private static final String SHADER_NS = "illusionindex:shaders/";

    /** Colour helpers -------------------------------------------------- */
    public static int color(float a, float r, float g, float b)
    {
        int ai = (int) (a * 255.0F + 0.5F) & 255;
        int ri = (int) (r * 255.0F + 0.5F) & 255;
        int gi = (int) (g * 255.0F + 0.5F) & 255;
        int bi = (int) (b * 255.0F + 0.5F) & 255;
        return ai << 24 | ri << 16 | gi << 8 | bi;
    }

    public static float[] rgba(int argb)
    {
        return new float[] {
            (argb >> 16 & 255) / 255.0F,
            (argb >> 8 & 255) / 255.0F,
            (argb & 255) / 255.0F,
            (argb >>> 24) / 255.0F
        };
    }

    public static int withAlpha(int argb, float a)
    {
        return ((int) (a * 255.0F + 0.5F) & 255) << 24 | (argb & 0xFFFFFF);
    }

    public static int fromHex(String hex)
    {
        long v = Long.parseLong(hex, 16);
        return (int) v;
    }

    public static String toHex(int rgb)
    {
        return String.format("%06X", Integer.valueOf(rgb & 0xFFFFFF));
    }

    private final Minecraft mc = Minecraft.getMinecraft();

    /** Logical (gui scaled) resolution. */
    public int logicalW;
    public int logicalH;
    /** Supersample factor of the UI framebuffer. */
    private int ss = 2;
    /** False when GLSL/FBOs are unavailable -> plain rectangles. */
    public boolean soft = true;

    private Fbo uiFbo;
    private Fbo blurA;
    private Fbo blurB;
    private int bgTex = -1;
    private int bgW = -1;
    private int bgH = -1;

    private int progRect;
    private int progTex;
    private int progBlur;
    private final Map<String, Integer> locCache = new HashMap<String, Integer>();

    // bilinear filtering for the vanilla font sheet while the UI is drawn
    private int fontTexId = -1;
    private int fontMinPrev = -1;
    private int fontMagPrev = -1;

    // GL state captured when the GUI frame started
    private boolean stBlend, stAlpha, stDepth, stLighting, stCull, stTexture;
    private int stSrc, stDst, stFbo;
    private int stVpX, stVpY, stVpW, stVpH;

    /** (Re-)create all GPU resources for the given logical resolution. */
    public void init(int logicalWidth, int logicalHeight)
    {
        this.logicalW = Math.max(320, logicalWidth);
        this.logicalH = Math.max(240, logicalHeight);

        int maxSide = Math.max(this.logicalW, this.logicalH);
        this.ss = 2;
        while (this.logicalW * this.ss > 4096 || this.logicalH * this.ss > 2304)
        {
            this.ss--;
            if (this.ss < 1)
            {
                this.ss = 1;
                break;
            }
        }
        if (maxSide >= 3840)
        {
            this.ss = Math.max(1, this.ss - 1);
        }

        this.progRect = createProgram("gui_quad.vert", "gui_rect.frag");
        this.progTex = createProgram("gui_quad.vert", "gui_tex.frag");
        this.progBlur = createProgram("gui_quad.vert", "gui_blur.frag");
        this.soft = this.progRect != 0 && this.progTex != 0 && this.progBlur != 0;
        if (this.soft)
        {
            this.uiFbo = new Fbo(this.logicalW * this.ss, this.logicalH * this.ss);
            this.blurA = new Fbo(Math.max(2, this.logicalW / 4), Math.max(2, this.logicalH / 4));
            this.blurB = new Fbo(this.blurA.w, this.blurA.h);
            this.bgTex = GL11.glGenTextures();
        }
    }

    /** Start a frame: capture + blur the backdrop, then draw into the UI fbo. */
    public void beginFrame()
    {
        this.saveGuiState();
        if (this.soft)
        {
            this.captureBackdrop();
            this.blurBackdrop();

            this.bindFbo(this.uiFbo, this.uiFbo.w, this.uiFbo.h);
            this.orthoYDown(this.logicalW, this.logicalH);
            this.uiState();
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
        else
        {
            this.orthoYDown(this.logicalW, this.logicalH);
            this.uiState();
        }
    }

    /** Composite the UI framebuffer onto the original target and restore GL. */
    public void endFrame()
    {
        if (this.soft)
        {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, this.stFbo);
            GL11.glViewport(this.stVpX, this.stVpY, this.stVpW, this.stVpH);
            this.orthoYDown(this.logicalW, this.logicalH);
            this.uiState();

            GL20.glUseProgram(this.progTex);
            setI(this.progTex, "u_tex", 0);
            setF4(this.progTex, "u_tint", 1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.uiFbo.tex);
            drawQuad(0.0F, 0.0F, this.logicalW, this.logicalH, false);
            GL20.glUseProgram(0);
        }
        this.restoreGuiState();
    }

    /** Semi-transparent blurred backdrop + dark dimming layer. */
    public void drawBackdrop(float dimAlpha)
    {
        if (this.soft && this.blurB != null)
        {
            GL11.glDisable(GL11.GL_BLEND);
            GL20.glUseProgram(this.progTex);
            setI(this.progTex, "u_tex", 0);
            setF4(this.progTex, "u_tint", 1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.blurB.tex);
            drawQuad(0.0F, 0.0F, this.logicalW, this.logicalH, false);
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_BLEND);
        }
        else
        {
            GL11.glEnable(GL11.GL_BLEND);
        }
        fillRounded(0.0F, 0.0F, this.logicalW, this.logicalH, 0.0F,
                color(dimAlpha, 0.03F, 0.04F, 0.06F), 0, 0.0F);
    }

    // ----------------------------------------------------------------
    // primitives (logical coordinates, y-down)
    // ----------------------------------------------------------------

    /** Rounded rect: optional fill and/or border. Colours are 0xAARRGGBB. */
    public void fillRounded(float x, float y, float w, float h, float radius, int fill, int border, float borderW)
    {
        if (w <= 0.0F || h <= 0.0F)
        {
            return;
        }
        if (!this.soft)
        {
            fallbackRect(x, y, w, h, fill, border, borderW);
            return;
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL20.glUseProgram(this.progRect);
        setF2(this.progRect, "u_vp", (float) this.uiFbo.w, (float) this.uiFbo.h);
        setF1(this.progRect, "u_ss", (float) this.ss);
        setF4(this.progRect, "u_rect", x, y, w, h);
        setF1(this.progRect, "u_radius", radius);
        float[] f = rgba(fill);
        setF4(this.progRect, "u_fill", f[0], f[1], f[2], f[3]);
        float[] b = rgba(border);
        setF4(this.progRect, "u_border", b[0], b[1], b[2], b[3]);
        setF1(this.progRect, "u_borderWidth", borderW);
        drawQuad(x, y, w, h, false);
        GL20.glUseProgram(0);
    }

    /** Vanilla font renderer text (drawn under the supersampling transform). */
    public void text(FontRenderer font, String s, float x, float y, int color, boolean shadow)
    {
        if (font == null || s == null || s.isEmpty())
        {
            return;
        }
        this.ensureFontSmooth();
        // the active viewport already maps logical units onto the ss-res framebuffer,
        // so glyphs are rasterised at ss resolution and get anti-aliased by the
        // final downsample - no extra scale matrix required.
        font.drawString(s, x, y, color, shadow);
    }

    /** Text with a hard outline for readability on bright backdrops. */
    public void textOutlined(FontRenderer font, String s, float x, float y, int color, int outline)
    {
        if (font == null || s == null || s.isEmpty())
        {
            return;
        }
        this.ensureFontSmooth();
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                if (dx == 0 && dy == 0)
                {
                    continue;
                }
                font.drawString(s, x + dx, y + dy, outline, false);
            }
        }
        font.drawString(s, x, y, color, false);
    }

    // ----------------------------------------------------------------
    // internals
    // ----------------------------------------------------------------

    private void captureBackdrop()
    {
        int w = this.mc.displayWidth;
        int h = this.mc.displayHeight;
        if (this.bgTex < 0)
        {
            this.bgTex = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.bgTex);
        if (w != this.bgW || h != this.bgH)
        {
            this.bgW = w;
            this.bgH = h;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

        try
        {
            // copy the world that was just rendered (mc's framebuffer)
            int framebuffer = this.mc.getFramebuffer().framebufferObject;
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        }
        catch (Throwable t)
        {
            this.soft = false;
        }
    }

    private void blurBackdrop()
    {
        if (this.bgTex < 0)
        {
            return;
        }
        // GL-rendered framebuffer textures store their top row at v=1, so the
        // top quad edge samples v=1 (flipV = false).
        // pass 1: horizontal blur while downsampling into blurA
        this.bindFbo(this.blurA, this.blurA.w, this.blurA.h);
        this.orthoYDown(this.blurA.w, this.blurA.h);
        GL20.glUseProgram(this.progBlur);
        setI(this.progBlur, "u_tex", 0);
        setF2(this.progBlur, "u_dir", 1.0F, 0.0F);
        setF2(this.progBlur, "u_texel", 1.0F / this.blurA.w, 1.0F / this.blurA.h);
        setF1(this.progBlur, "u_radius", 2.5F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.bgTex);
        drawQuad(0.0F, 0.0F, this.blurA.w, this.blurA.h, false);
        GL20.glUseProgram(0);

        // pass 2: vertical blur into blurB
        this.bindFbo(this.blurB, this.blurB.w, this.blurB.h);
        this.orthoYDown(this.blurB.w, this.blurB.h);
        GL20.glUseProgram(this.progBlur);
        setI(this.progBlur, "u_tex", 0);
        setF2(this.progBlur, "u_dir", 0.0F, 1.0F);
        setF2(this.progBlur, "u_texel", 1.0F / this.blurA.w, 1.0F / this.blurA.h);
        setF1(this.progBlur, "u_radius", 2.5F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.blurA.tex);
        drawQuad(0.0F, 0.0F, this.blurB.w, this.blurB.h, false);
        GL20.glUseProgram(0);
    }

    private void bindFbo(Fbo fbo, int vw, int vh)
    {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo == null ? 0 : fbo.fbo);
        GL11.glViewport(0, 0, vw, vh);
    }

    /** y-down ortho: logical (0,0) is the top-left corner. */
    private void orthoYDown(float w, float h)
    {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, w, h, 0.0D, -1.0D, 1.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
    }

    private void uiState()
    {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void saveGuiState()
    {
        this.stBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        this.stAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        this.stDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.stLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        this.stCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.stTexture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        this.stSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        this.stDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        this.stFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        // LWJGL requires a 16 element buffer for glGetInteger queries
        IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        this.stVpX = vp.get(0);
        this.stVpY = vp.get(1);
        this.stVpW = vp.get(2);
        this.stVpH = vp.get(3);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
    }

    private void restoreGuiState()
    {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();

        if (!this.stBlend)
        {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (this.stAlpha)
        {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
        }
        if (this.stDepth)
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        if (this.stLighting)
        {
            GL11.glEnable(GL11.GL_LIGHTING);
        }
        if (this.stCull)
        {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        if (!this.stTexture)
        {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL11.glBlendFunc(this.stSrc, this.stDst);
        GL11.glViewport(this.stVpX, this.stVpY, this.stVpW, this.stVpH);
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, this.stFbo);
    }

    /**
     * Switches the vanilla glyph sheet to bilinear filtering while the UI is
     * rendered (the supersampling downsample then anti-aliases the glyphs).
     * The previous filter of the sheet is restored in {@link #destroy()}.
     */
    private void ensureFontSmooth()
    {
        if (this.mc.fontRendererObj == null)
        {
            return;
        }
        try
        {
            // put the previously smoothed sheet back to its original filter first
            if (this.fontTexId != -1)
            {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fontTexId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, this.fontMinPrev);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, this.fontMagPrev);
            }
            this.mc.getTextureManager().bindTexture(new ResourceLocation("textures/font/ascii.png"));
            int id = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (id != this.fontTexId)
            {
                this.fontTexId = id;
                this.fontMinPrev = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
                this.fontMagPrev = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            }
            else
            {
                this.fontMinPrev = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
                this.fontMagPrev = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }
        catch (Throwable t)
        {
            // font sheet unavailable - draw with the default filter
        }
    }

    private void restoreFontFilter()
    {
        if (this.fontTexId != -1)
        {
            try
            {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.fontTexId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, this.fontMinPrev);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, this.fontMagPrev);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            }
            catch (Throwable t)
            {
                // ignore
            }
            this.fontTexId = -1;
        }
    }

    /** Immediate quad; flipV lets v=1 sit on the top edge. */
    private static void drawQuad(float x, float y, float w, float h, boolean flipV)
    {
        float v0 = flipV ? 1.0F : 0.0F;
        float v1 = flipV ? 0.0F : 1.0F;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, v1);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(1.0F, v1);
        GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(1.0F, v0);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(0.0F, v0);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static void fallbackRect(float x, float y, float w, float h, int fill, int border, float borderW)
    {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if ((fill >>> 24) != 0)
        {
            float[] c = rgba(fill);
            GL11.glColor4f(c[0], c[1], c[2], c[3]);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        }
        if ((border >>> 24) != 0 && borderW > 0.0F)
        {
            float[] c = rgba(border);
            GL11.glColor4f(c[0], c[1], c[2], c[3]);
            GL11.glLineWidth(borderW);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private int createProgram(String vertFile, String fragFile)
    {
        String vs = loadShader(vertFile);
        String fs = fragFile == null ? "" : loadShader(fragFile);
        if (vs.isEmpty() || (fragFile != null && fs.isEmpty()))
        {
            return 0;
        }
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        if (v == 0)
        {
            return 0;
        }
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);
        if (GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            GL20.glDeleteShader(v);
            return 0;
        }

        int f = 0;
        if (fragFile != null)
        {
            f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            if (f == 0)
            {
                return 0;
            }
            GL20.glShaderSource(f, fs);
            GL20.glCompileShader(f);
            if (GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
            {
                GL20.glDeleteShader(v);
                GL20.glDeleteShader(f);
                return 0;
            }
        }

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        if (f != 0)
        {
            GL20.glAttachShader(p, f);
        }
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(v);
        if (f != 0)
        {
            GL20.glDeleteShader(f);
        }
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            GL20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private String loadShader(String file)
    {
        try
        {
            InputStream in = this.mc.getResourceManager()
                    .getResource(new ResourceLocation(SHADER_NS + file)).getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1)
            {
                out.write(buf, 0, r);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        }
        catch (Throwable t)
        {
            return "";
        }
    }

    private int loc(int program, String name)
    {
        String key = program + ":" + name;
        Integer cached = this.locCache.get(key);
        if (cached != null)
        {
            return cached.intValue();
        }
        int l = GL20.glGetUniformLocation(program, name);
        this.locCache.put(key, Integer.valueOf(l));
        return l;
    }

    private void setI(int p, String name, int v)
    {
        GL20.glUniform1i(loc(p, name), v);
    }

    private void setF1(int p, String name, float v)
    {
        GL20.glUniform1f(loc(p, name), v);
    }

    private void setF2(int p, String name, float a, float b)
    {
        GL20.glUniform2f(loc(p, name), a, b);
    }

    private void setF4(int p, String name, float a, float b, float c, float d)
    {
        GL20.glUniform4f(loc(p, name), a, b, c, d);
    }

    /** Frees every GPU resource (GUI closed). */
    public void destroy()
    {
        this.restoreFontFilter();
        if (this.uiFbo != null)
        {
            this.uiFbo.delete();
            this.uiFbo = null;
        }
        if (this.blurA != null)
        {
            this.blurA.delete();
            this.blurA = null;
        }
        if (this.blurB != null)
        {
            this.blurB.delete();
            this.blurB = null;
        }
        if (this.bgTex >= 0)
        {
            GL11.glDeleteTextures(this.bgTex);
            this.bgTex = -1;
        }
        if (this.progRect != 0)
        {
            GL20.glDeleteProgram(this.progRect);
        }
        if (this.progTex != 0)
        {
            GL20.glDeleteProgram(this.progTex);
        }
        if (this.progBlur != 0)
        {
            GL20.glDeleteProgram(this.progBlur);
        }
        this.progRect = this.progTex = this.progBlur = 0;
        this.locCache.clear();
    }

    /** FBO wrapper (colour only - no depth needed for 2D UI). */
    private static final class Fbo
    {
        final int w;
        final int h;
        final int fbo;
        final int tex;

        Fbo(int w, int h)
        {
            this.w = w;
            this.h = h;
            this.tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

            this.fbo = OpenGlHelper.glGenFramebuffers();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, this.fbo);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER,
                    OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.tex, 0);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        }

        void delete()
        {
            GL11.glDeleteTextures(this.tex);
            OpenGlHelper.glDeleteFramebuffers(this.fbo);
        }
    }
}
