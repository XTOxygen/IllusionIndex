package com.endwithme.illusionindex.gui;

import com.endwithme.illusionindex.Config;
import com.endwithme.illusionindex.IllusionIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The shader driven IllusionIndex settings screen.
 *
 * <p>UI style: semi-transparent dark cards with white 1px borders and rounded
 * corners (GLSL rounded-rect shader) above a blurred, dimmed copy of the world;
 * pop-in / pop-out animation; pill switches (ON = white fill with black knob,
 * OFF = transparent fill, white border, black knob); sliders with a
 * semi-transparent white track and a white rounded-square handle; colour
 * selection via 16 preset swatches plus an RGB hex input.
 *
 * <p>Text is drawn with the vanilla font renderer but supersampled (the whole
 * UI is rasterised into a 2x framebuffer) with the font sheet bilinear
 * filtered, plus outlines/shadows for readability. Keyboard navigation:
 * Tab/Shift+Tab cycles focus, arrows adjust the focused slider, Enter
 * confirms/activates, Esc closes. Every change applies instantly and is
 * persisted into the config file.
 *
 * @author EndWithMe
 */
public class GuiConfig extends GuiScreen
{

    private static final int CLR_TEXT     = 0xFFFFFFFF;
    private static final int CLR_TEXT_SUB = 0xBFB9C2CC;
    private static final int CLR_TEXT_DIM = 0x9FA7AEB8;
    private static final int CLR_ACCENT   = 0xFF6FB4FF;
    private static final int CLR_CARD     = 0xDE0C0E12;
    private static final int CLR_BORDER   = 0xE6FFFFFF;
    private static final int CLR_KNOB     = 0xFF101014;
    private static final float RAD_CARD   = 12.0F;

    private static final int[] PRESETS = {
        0x000000, 0x2B2B2B, 0x666666, 0x9E9E9E, 0xFFFFFF,
        0x7F0000, 0xFF0000, 0xFF8000, 0xFFFF00, 0x80FF00,
        0x00FF80, 0x00FFFF, 0x0080FF, 0x0000FF, 0x8000FF, 0xFF00FF
    };

    /** Vertical space reserved for the title block above the cards. */
    private static final float HEADER_H = 56.0F;

    private enum Type { TOGGLE, SLIDER, COLOR, KEY }

    /** java.util.function has no boolean consumer, so this tiny stand-in is used. */
    private interface BooleanConsumer
    {
        void accept(boolean value);
    }

    private static final BooleanSupplier YES = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean()
        {
            return true;
        }
    };
    private static final BooleanSupplier NO = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean()
        {
            return false;
        }
    };

    private static final class Row
    {
        final Type type;
        final String label;
        final String hint;
        final BooleanSupplier getB;
        final BooleanConsumer setB;
        final DoubleSupplier getD;
        final DoubleConsumer setD;
        final double min, max, step;
        final IntSupplier getI;
        final IntConsumer setI;
        final BooleanSupplier parent;      // gate: row disabled while parent toggle is off
        float y, h;
        float anim;                        // animated control value 0..1

        Row(Type type, String label, String hint, boolean active)
        {
            this(type, label, hint, null, null, null, null, 0, 0, 0, null, null,
                    active ? YES : NO);
        }

        Row(Type type, String label, String hint, boolean active,
            BooleanSupplier g, BooleanConsumer s)
        {
            this(type, label, hint, g, s, null, null, 0, 0, 0, null, null,
                    active ? YES : NO);
        }

        Row(Type type, String label, String hint, boolean active,
            IntSupplier gi, IntConsumer si)
        {
            this(type, label, hint, null, null, null, null, 0, 0, 0, gi, si,
                    active ? YES : NO);
        }

        Row(Type type, String label, String hint,
            DoubleSupplier gd, DoubleConsumer sd, double min, double max, double step,
            BooleanSupplier parent)
        {
            this(type, label, hint, null, null, gd, sd, min, max, step, null, null, parent);
        }

        Row(Type type, String label, String hint,
            BooleanSupplier g, BooleanConsumer s,
            DoubleSupplier gd, DoubleConsumer sd, double min, double max, double step,
            IntSupplier gi, IntConsumer si, BooleanSupplier parent)
        {
            this.type = type;
            this.label = label;
            this.hint = hint;
            this.getB = g;
            this.setB = s;
            this.getD = gd;
            this.setD = sd;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getI = gi;
            this.setI = si;
            this.parent = parent;
            this.h = type == Type.COLOR ? 86.0F : 38.0F;
        }
    }

    private static final class Card
    {
        final String title;
        final List<Row> rows = new ArrayList<Row>();
        float y;

        Card(String title)
        {
            this.title = title;
        }
    }

    // ---------------------------------------------------------------
    // state
    // ---------------------------------------------------------------

    private final Config cfg;
    private final List<Card> cards = new ArrayList<Card>();
    private final List<Row> focusables = new ArrayList<Row>();
    private final UiRenderer ui = new UiRenderer();

    private long lastNanos;
    private float openT = -1.0F;
    private float closeT = -1.0F;
    private float scroll;
    private float scrollTarget;
    private float headerY = 18.0F;
    private int focusIndex;
    private boolean captureKey;
    private String hexBuf = "";
    private Row dragRow;

    public GuiConfig()
    {
        this.cfg = IllusionIndex.instance.config;
    }

    // ---------------------------------------------------------------
    // GuiScreen
    // ---------------------------------------------------------------

    @Override
    public void initGui()
    {
        this.ui.init(this.width, this.height);
        this.buildCards();
        this.focusIndex = 0;
        this.openT = 0.0F;
        this.closeT = -1.0F;
        this.scroll = this.scrollTarget = 0.0F;
        this.hexBuf = UiRenderer.toHex(this.cfg.baseColor);
        this.captureKey = false;
        this.dragRow = null;
        this.lastNanos = System.nanoTime();
    }

    @Override
    public void onGuiClosed()
    {
        this.ui.destroy();
        this.cfg.save();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks)
    {
        long now = System.nanoTime();
        float dt = Math.min(0.1F, (float) ((now - this.lastNanos) / 1.0e9D));
        this.lastNanos = now;

        if (this.openT >= 0.0F && this.openT < 1.0F)
        {
            this.openT = Math.min(1.0F, this.openT + dt / 0.22F);
        }
        if (this.closeT >= 0.0F)
        {
            this.closeT = Math.min(1.0F, this.closeT + dt / 0.15F);
            if (this.closeT >= 1.0F)
            {
                this.mc.displayGuiScreen(null);
                return;
            }
        }

        float eased = this.closeT >= 0.0F ? easeInCubic(1.0F - this.closeT) : easeOutBack(this.openT);
        float alpha = this.closeT >= 0.0F ? 1.0F - this.closeT : Math.min(1.0F, this.openT * 5.0F);

        this.layout();

        this.ui.beginFrame();
        this.ui.drawBackdrop(0.60F);

        // pop animation around the vertical centre of the panel stack
        float pop = 0.90F + 0.10F * eased;
        float cx = this.width / 2.0F;
        float cy = clamp(this.height / 2.0F + 30.0F, 120.0F, this.height - 60.0F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        GlStateManager.scale(pop, pop, 1.0F);
        GlStateManager.translate(-cx, -cy, 0.0F);

        this.drawHeader();
        for (Card card : this.cards)
        {
            this.drawCard(card, mouseX, mouseY, dt);
        }
        GlStateManager.popMatrix();

        // bottom hint
        this.ui.text(this.fontRendererObj,
                "\u00a77Tab/Shift+Tab\u00a7r switch focus   \u00a77arrows\u00a7r adjust   "
                        + "\u00a77Enter\u00a7r confirm   \u00a77Esc\u00a7r close",
                20, this.height - 13, CLR_TEXT_DIM, false);

        // fade veil while opening/closing
        if (alpha < 0.999F)
        {
            this.ui.fillRounded(0, 0, this.width, this.height, 0,
                    UiRenderer.color((1.0F - alpha) * 0.9F, 0.0F, 0.0F, 0.0F), 0, 0);
        }
        this.ui.endFrame();
    }

    private void drawHeader()
    {
        float hx = (this.width - cardWidth()) / 2.0F;
        float hy = this.headerY;
        this.ui.fillRounded(hx, hy + 4, 4, 24, 2, CLR_ACCENT, 0, 0);
        this.ui.textOutlined(this.fontRendererObj, "IllusionIndex", hx + 13, hy, CLR_TEXT, 0xFF000000);
        this.ui.text(this.fontRendererObj, "Invisible player reveal - by EndWithMe",
                hx + 14, hy + 15, CLR_TEXT_SUB, true);
    }

    private void drawCard(Card card, int mouseX, int mouseY, float dt)
    {
        float cw = cardWidth();
        float cx = (this.width - cw) / 2.0F;
        float cy = card.y + this.scroll;

        this.ui.fillRounded(cx, cy, cw, cardHeight(card), RAD_CARD, CLR_CARD, CLR_BORDER, 1.0F);
        this.ui.text(this.fontRendererObj, card.title, cx + 18, cy + 13, CLR_TEXT, true);
        this.ui.fillRounded(cx + 18, cy + 26, 26, 2, 1, CLR_ACCENT, 0, 0);

        float ry = cy + 40.0F;
        for (Row row : card.rows)
        {
            row.y = ry;
            this.animateRow(row, dt);
            this.drawRow(row, cx, cw, mouseX, mouseY);
            ry += row.h;
        }
    }

    private void drawRow(Row row, float cardX, float cardW, int mouseX, int mouseY)
    {
        float x = cardX + 18.0F;
        float right = cardX + cardW - 18.0F;
        boolean dim = !this.rowEnabled(row);
        boolean focused = !this.captureKey && this.focusables.size() > this.focusIndex
                && this.focusables.get(this.focusIndex) == row;

        switch (row.type)
        {
            case TOGGLE:
            {
                this.ui.text(this.fontRendererObj, row.label, x, row.y + 3, dim ? CLR_TEXT_DIM : CLR_TEXT, true);
                if (row.hint != null)
                {
                    this.ui.text(this.fontRendererObj, row.hint, x, row.y + 14, dim ? CLR_TEXT_DIM : CLR_TEXT_SUB, true);
                }
                float tw = 46.0F;
                float th = 24.0F;
                float tx = right - tw;
                float ty = row.y + 4;
                drawSwitch(tx, ty, tw, th, row.anim);
                if (focused)
                {
                    drawFocusRing(tx - 3, ty - 3, tw + 6, th + 6);
                }
                break;
            }
            case SLIDER:
            {
                this.ui.text(this.fontRendererObj, row.label, x, row.y + 3, dim ? CLR_TEXT_DIM : CLR_TEXT, true);
                if (row.hint != null)
                {
                    this.ui.text(this.fontRendererObj, row.hint, x, row.y + 14, dim ? CLR_TEXT_DIM : CLR_TEXT_SUB, true);
                }
                float valW = 52.0F;
                float trackW = 130.0F;
                float trackX = right - valW - trackW;
                float ty = row.y + 10;
                float ratio = row.anim;
                this.ui.fillRounded(trackX, ty + 5, trackW, 4, 2,
                        UiRenderer.color(0.16F, 1, 1, 1), 0, 0);
                this.ui.fillRounded(trackX, ty + 5, Math.max(4.0F, trackW * ratio), 4, 2,
                        UiRenderer.color(focused ? 0.55F : 0.36F, 1, 1, 1), 0, 0);
                float hx = trackX + trackW * ratio - 8.0F;
                this.ui.fillRounded(hx, ty, 16, 16, 4.5F, 0xFFFFFFFF,
                        UiRenderer.color(0.30F, 0, 0, 0), 1.0F);
                String value = formatValue(row);
                int vw = this.fontRendererObj.getStringWidth(value);
                this.ui.text(this.fontRendererObj, value, right - vw, row.y + 9,
                        dim ? CLR_TEXT_DIM : 0xFFD8DEE8, true);
                if (focused)
                {
                    drawFocusRing(trackX - 3, ty - 4, trackW + 6, 24);
                }
                break;
            }
            case COLOR:
            {
                this.ui.text(this.fontRendererObj, row.label, x, row.y + 2, dim ? CLR_TEXT_DIM : CLR_TEXT, true);
                // hex box + preview chip
                float chipW = 22.0F;
                float chipX = right - chipW;
                float hexW = 100.0F;
                float hexX = chipX - 10.0F - hexW;
                this.ui.fillRounded(hexX, row.y + 1, hexW, 21, 6,
                        UiRenderer.color(0.09F, 1, 1, 1),
                        focused ? CLR_ACCENT : UiRenderer.color(0.55F, 1, 1, 1), 1.0F);
                boolean hexOk = validHex();
                this.ui.text(this.fontRendererObj, "#" + this.hexBuf,
                        hexX + 9, row.y + 6, dim ? CLR_TEXT_DIM : (hexOk ? CLR_TEXT : 0xFFFF9090), true);
                if (focused)
                {
                    int tw2 = this.fontRendererObj.getStringWidth("#" + this.hexBuf);
                    this.ui.fillRounded(hexX + 10 + tw2 + 2, row.y + 6, 1, 10, 0, CLR_ACCENT, 0, 0);
                }
                this.ui.fillRounded(chipX, row.y + 4, chipW, 15, 4,
                        0xFF000000 | (this.cfg.baseColor & 0xFFFFFF),
                        UiRenderer.withAlpha(CLR_BORDER, dim ? 0.3F : 0.7F), 1.0F);

                // 16 preset swatches in a single row
                int n = PRESETS.length;
                float gap = 7.0F;
                float area = cardW - 36.0F;
                float sw = Math.min(24.0F, (area - gap * (n - 1)) / n);
                float sy = row.y + 32.0F;
                for (int i = 0; i < n; i++)
                {
                    float sx = x + i * (sw + gap);
                    boolean selected = (this.cfg.baseColor & 0xFFFFFF) == PRESETS[i];
                    boolean swHover = mouseY >= sy - 2 && mouseY <= sy + sw + 2
                            && mouseX >= sx - 2 && mouseX <= sx + sw + 2;
                    this.ui.fillRounded(sx, sy, sw, sw, 5, 0xFF000000 | PRESETS[i],
                            selected ? CLR_ACCENT
                                    : (swHover ? UiRenderer.color(0.85F, 1, 1, 1)
                                    : UiRenderer.color(0.45F, 1, 1, 1)),
                            selected ? 2.0F : 1.0F);
                    if (selected)
                    {
                        int mark = (PRESETS[i] == 0xFFFFFF || PRESETS[i] == 0x00FFFF
                                || PRESETS[i] == 0xFFFF00 || PRESETS[i] == 0x80FF00) ? 0xFF000000 : 0xFFFFFFFF;
                        this.ui.fillRounded(sx + sw / 2.0F - 2.5F, sy + sw - 8.0F, 5, 5, 1.5F, mark, 0, 0);
                    }
                }
                break;
            }
            case KEY:
            {
                this.ui.text(this.fontRendererObj, row.label, x, row.y + 3, dim ? CLR_TEXT_DIM : CLR_TEXT, true);
                if (row.hint != null)
                {
                    this.ui.text(this.fontRendererObj, row.hint, x, row.y + 14, dim ? CLR_TEXT_DIM : CLR_TEXT_SUB, true);
                }
                float bw = 140.0F;
                float bx = right - bw;
                boolean capturing = this.captureKey && focused;
                this.ui.fillRounded(bx, row.y + 2, bw, 22, 6,
                        UiRenderer.color(capturing ? 0.25F : 0.09F, 1, 1, 1),
                        capturing ? CLR_ACCENT : UiRenderer.color(0.55F, 1, 1, 1), 1.0F);
                String txt = capturing ? "Press a key..." : keyName(this.cfg.keyOpenGui);
                this.ui.text(this.fontRendererObj, txt, bx + 12, row.y + 7,
                        dim ? CLR_TEXT_DIM : (capturing ? CLR_ACCENT : CLR_TEXT), true);
                if (focused && !capturing)
                {
                    drawFocusRing(bx - 3, row.y - 1, bw + 6, 26);
                }
                break;
            }
            default:
                break;
        }
    }

    // ---------------------------------------------------------------
    // input
    // ---------------------------------------------------------------

    @Override
    protected void keyTyped(char typedChar, int keyCode)
    {
        if (this.captureKey)
        {
            if (keyCode == Keyboard.KEY_ESCAPE)
            {
                this.captureKey = false;
            }
            else if (keyCode != 0)
            {
                IllusionIndex.instance.keyBindings.setOpenGuiKey(keyCode);
                this.captureKey = false;
            }
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE)
        {
            if (this.closeT < 0.0F)
            {
                this.closeT = 0.0F;
            }
            return;
        }

        Row row = this.focusedRow();
        if (row == null)
        {
            return;
        }

        if (keyCode == Keyboard.KEY_TAB)
        {
            boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            int dir = shift ? -1 : 1;
            this.focusIndex = (this.focusIndex + dir + this.focusables.size()) % this.focusables.size();
            this.ensureFocusVisible();
            return;
        }

        // typing inside the hex field
        if (row.type == Type.COLOR)
        {
            if (keyCode == Keyboard.KEY_RETURN)
            {
                this.commitHex();
                return;
            }
            if (keyCode == Keyboard.KEY_BACK)
            {
                if (!this.hexBuf.isEmpty())
                {
                    this.hexBuf = this.hexBuf.substring(0, this.hexBuf.length() - 1);
                }
                return;
            }
            if (this.hexBuf.length() < 6 && "0123456789abcdefABCDEF".indexOf(typedChar) >= 0)
            {
                this.hexBuf = this.hexBuf + Character.toUpperCase(typedChar);
                if (this.validHex())
                {
                    this.cfg.baseColor = UiRenderer.fromHex(this.hexBuf);
                    this.cfg.save();
                }
            }
            return;
        }

        if (row.type == Type.SLIDER)
        {
            if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT)
            {
                double dir = keyCode == Keyboard.KEY_RIGHT ? 1.0D : -1.0D;
                double v = clamp(row.getD.getAsDouble() + dir * row.step, row.min, row.max);
                row.setD.accept(v);
                this.cfg.save();
            }
            return;
        }

        if (keyCode == Keyboard.KEY_RETURN)
        {
            if (row.type == Type.TOGGLE && this.rowEnabled(row))
            {
                row.setB.accept(!row.getB.getAsBoolean());
                this.cfg.save();
            }
            else if (row.type == Type.KEY)
            {
                this.captureKey = true;
            }
        }
    }

    /**
     * Vanilla calls this once per raw mouse event (see GuiScreen.handleInput),
     * with Mouse pointing at the current event - so only the current event may
     * be consumed here. Wheel events additionally scroll the panel stack.
     */
    @Override
    public void handleMouseInput() throws IOException
    {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0)
        {
            this.scrollTarget -= Math.signum(wheel) * 28.0F;
        }
        int mx = Mouse.getEventX() * this.width / Math.max(1, this.mc.displayWidth);
        int my = this.height - Mouse.getEventY() * this.height / Math.max(1, this.mc.displayHeight) - 1;
        int btn = Mouse.getEventButton();
        if (Mouse.getEventButtonState())
        {
            this.mouseClicked(mx, my, btn);
        }
        else if (btn != -1)
        {
            this.dragRow = null;
            this.mouseReleased(mx, my, btn);
        }
        else if (this.dragRow != null)
        {
            this.mouseClickMove(mx, my, 0, 0L);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        if (mouseButton != 0)
        {
            return;
        }
        Row row = this.rowAt(mouseX, mouseY);
        if (row == null)
        {
            this.captureKey = false;
            return;
        }
        int idx = this.focusables.indexOf(row);
        if (idx >= 0)
        {
            this.focusIndex = idx;
        }
        if (this.captureKey)
        {
            this.captureKey = false;
            return;
        }
        if (!this.rowEnabled(row))
        {
            return;
        }

        float cardX = (this.width - cardWidth()) / 2.0F;
        float x0 = cardX + 18.0F;
        float right = cardX + cardWidth() - 18.0F;

        switch (row.type)
        {
            case TOGGLE:
                row.setB.accept(!row.getB.getAsBoolean());
                this.cfg.save();
                break;
            case SLIDER:
                this.dragRow = row;
                this.sliderValueFromMouse(row, mouseX);
                break;
            case COLOR:
            {
                float chipX = right - 22.0F;
                float hexW = 100.0F;
                float hexX = chipX - 10.0F - hexW;
                if (mouseX >= hexX && mouseX <= hexX + hexW && mouseY >= row.y + 1 && mouseY <= row.y + 22)
                {
                    this.hexBuf = UiRenderer.toHex(this.cfg.baseColor);
                    return;
                }
                int n = PRESETS.length;
                float gap = 7.0F;
                float area = cardWidth() - 36.0F;
                float sw = Math.min(24.0F, (area - gap * (n - 1)) / n);
                float sy = row.y + 32.0F;
                for (int i = 0; i < n; i++)
                {
                    float sx = x0 + i * (sw + gap);
                    if (mouseX >= sx && mouseX <= sx + sw && mouseY >= sy && mouseY <= sy + sw)
                    {
                        this.cfg.baseColor = PRESETS[i];
                        this.hexBuf = UiRenderer.toHex(this.cfg.baseColor);
                        this.cfg.save();
                        return;
                    }
                }
                break;
            }
            case KEY:
                this.captureKey = true;
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state)
    {
        this.dragRow = null;
        this.cfg.save();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick)
    {
        if (this.dragRow != null && this.dragRow.type == Type.SLIDER)
        {
            this.sliderValueFromMouse(this.dragRow, mouseX);
        }
    }

    private void sliderValueFromMouse(Row row, int mouseX)
    {
        float cardX = (this.width - cardWidth()) / 2.0F;
        float trackX = cardX + cardWidth() - 18.0F - 52.0F - 130.0F;
        double ratio = (mouseX - trackX) / 130.0D;
        double v = clamp(row.min + ratio * (row.max - row.min), row.min, row.max);
        v = roundStep(v, row.step, row.min);
        row.setD.accept(v);
    }

    // ---------------------------------------------------------------
    // widgets / helpers
    // ---------------------------------------------------------------

    /** Pill switch: ON = white fill + black knob, OFF = transparent + white border + black knob. */
    private void drawSwitch(float x, float y, float w, float h, float progress)
    {
        boolean on = progress > 0.5F;
        this.ui.fillRounded(x, y, w, h, h / 2.0F,
                on ? 0xFFFFFFFF : UiRenderer.color(0.05F, 1, 1, 1),
                on ? 0xFFFFFFFF : UiRenderer.color(0.92F, 1, 1, 1), 1.0F);
        if (!on)
        {
            // faint dark fill so the black knob stays visible on the dark card
            this.ui.fillRounded(x + 1.5F, y + 1.5F, w - 3.0F, h - 3.0F, (h - 3.0F) / 2.0F,
                    UiRenderer.color(0.12F, 0, 0, 0), 0, 0);
        }
        float knob = h - 7.0F;
        float kx = x + 3.5F + (w - knob - 7.0F) * progress;
        this.ui.fillRounded(kx, y + 3.5F, knob, knob, knob / 2.0F,
                CLR_KNOB, UiRenderer.color(0.0F, 1, 1, 1), 0.5F);
    }

    private void drawFocusRing(float x, float y, float w, float h)
    {
        this.ui.fillRounded(x - 1, y - 1, w + 2, h + 2, Math.min(12.0F, h / 2.0F + 1.0F),
                0, UiRenderer.color(0.55F, 0.40F, 0.72F, 1.0F), 1.0F);
    }

    private void animateRow(Row row, float dt)
    {
        float target;
        if (row.type == Type.TOGGLE)
        {
            target = row.getB.getAsBoolean() ? 1.0F : 0.0F;
        }
        else if (row.type == Type.SLIDER && row.getD != null)
        {
            target = (float) ((row.getD.getAsDouble() - row.min) / (row.max - row.min));
        }
        else
        {
            return;
        }
        row.anim += (target - row.anim) * Math.min(1.0F, dt * 11.0F);
        if (Math.abs(target - row.anim) < 0.001F)
        {
            row.anim = target;
        }
    }

    private boolean rowEnabled(Row row)
    {
        return this.cfg.enabled && (row.parent == null || row.parent.getAsBoolean());
    }

    // ---------------------------------------------------------------
    // layout & hit testing
    // ---------------------------------------------------------------

    private float cardWidth()
    {
        return Math.min(620.0F, this.width - 44.0F);
    }

    private float cardHeight(Card card)
    {
        float h = 54.0F;
        for (Row r : card.rows)
        {
            h += r.h;
        }
        return h + 6.0F;
    }

    private void layout()
    {
        float blockH = HEADER_H;
        for (Card card : this.cards)
        {
            blockH += cardHeight(card) + 14.0F;
        }
        // centre the block vertically when it fits, otherwise scroll from the top
        float top = Math.max(12.0F, (this.height - blockH) / 2.0F);
        if (blockH > this.height - 24.0F)
        {
            top = 12.0F;
        }
        this.headerY = top;

        float y = top + HEADER_H;
        for (Card card : this.cards)
        {
            card.y = y;
            y += cardHeight(card) + 14.0F;
        }
        float maxScroll = Math.max(0.0F, y + 6.0F - this.height + 18.0F);
        this.scrollTarget = clamp(this.scrollTarget, -maxScroll, 0.0F);
        this.scroll += (this.scrollTarget - this.scroll) * 0.25F;
        this.scroll = clamp(this.scroll, -maxScroll, 0.0F);
    }

    private Row rowAt(int mx, int my)
    {
        float cx = (this.width - cardWidth()) / 2.0F;
        for (Card card : this.cards)
        {
            for (Row row : card.rows)
            {
                // row.y already includes the scroll offset (drawCard places it)
                float y0 = row.y;
                if (my >= y0 - 2 && my <= y0 + row.h + 2
                        && mx >= cx + 8 && mx <= cx + cardWidth() - 8)
                {
                    return row;
                }
            }
        }
        return null;
    }

    private Row focusedRow()
    {
        if (this.focusables.isEmpty())
        {
            return null;
        }
        return this.focusables.get(this.focusIndex % this.focusables.size());
    }

    private void ensureFocusVisible()
    {
        Row row = this.focusedRow();
        if (row == null)
        {
            return;
        }
        float top = row.y - 6.0F;
        float bottom = row.y + row.h + 8.0F;
        if (top < 46.0F)
        {
            this.scrollTarget += 46.0F - top;
        }
        if (bottom > this.height - 24.0F)
        {
            this.scrollTarget -= bottom - (this.height - 24.0F);
        }
    }

    // ---------------------------------------------------------------
    // model
    // ---------------------------------------------------------------

    private void buildCards()
    {
        this.cards.clear();
        this.focusables.clear();

        Card render = new Card("Render Settings");
        render.rows.add(new Row(Type.TOGGLE, "Observe invisible players",
                "Render them as translucent silhouettes in the base colour",
                true,
                () -> this.cfg.observeInvisible, b -> this.cfg.observeInvisible = b));
        render.rows.add(new Row(Type.SLIDER, "Silhouette opacity",
                "Alpha of the reveal silhouette",
                () -> this.cfg.opacity, v -> this.cfg.opacity = v,
                0.05D, 1.0D, 0.01D,
                () -> this.cfg.observeInvisible));
        render.rows.add(new Row(Type.COLOR, "Base colour",
                "Skin is never shown - players are forced to this colour",
                true,
                () -> this.cfg.baseColor, v -> this.cfg.baseColor = v));
        this.cards.add(render);

        Card after = new Card("Afterimage Settings");
        after.rows.add(new Row(Type.TOGGLE, "Afterimages",
                "Trails keep working even when observation is disabled",
                true,
                () -> this.cfg.afterimagesEnabled, b -> this.cfg.afterimagesEnabled = b));
        after.rows.add(new Row(Type.SLIDER, "Frame count",
                "Afterimage frames kept per player",
                () -> (double) this.cfg.afterimageCount, v -> this.cfg.afterimageCount = (int) Math.round(v),
                2.0D, 48.0D, 1.0D,
                () -> this.cfg.afterimagesEnabled));
        after.rows.add(new Row(Type.SLIDER, "Linger time",
                "Seconds afterimages stay before fading out",
                () -> this.cfg.afterimageLinger, v -> this.cfg.afterimageLinger = v,
                0.1D, 4.0D, 0.05D,
                () -> this.cfg.afterimagesEnabled));
        this.cards.add(after);

        Card general = new Card("General Settings");
        general.rows.add(new Row(Type.TOGGLE, "Enabled",
                "Master switch of IllusionIndex",
                true,
                () -> this.cfg.enabled, b -> this.cfg.enabled = b));
        general.rows.add(new Row(Type.TOGGLE, "Chat alerts",
                "Red bold [IIndex]:Spotted... when an invisible player enters attack range",
                true,
                () -> this.cfg.spotChatEnabled, b -> this.cfg.spotChatEnabled = b));
        general.rows.add(new Row(Type.SLIDER, "Spot range",
                "Attack range used by the chat alert",
                () -> this.cfg.spotRange, v -> this.cfg.spotRange = v,
                1.0D, 16.0D, 0.25D,
                () -> this.cfg.spotChatEnabled));
        general.rows.add(new Row(Type.KEY, "Open GUI key",
                "Click the key box, then press the desired key", true));
        this.cards.add(general);

        for (Card c : this.cards)
        {
            this.focusables.addAll(c.rows);
        }
    }

    // ---------------------------------------------------------------
    // value formatting & misc
    // ---------------------------------------------------------------

    private boolean validHex()
    {
        if (this.hexBuf == null || this.hexBuf.length() != 6)
        {
            return false;
        }
        for (int i = 0; i < 6; i++)
        {
            if ("0123456789abcdefABCDEF".indexOf(this.hexBuf.charAt(i)) < 0)
            {
                return false;
            }
        }
        return true;
    }

    private void commitHex()
    {
        if (this.validHex())
        {
            this.cfg.baseColor = UiRenderer.fromHex(this.hexBuf);
            this.cfg.save();
        }
        else
        {
            this.hexBuf = UiRenderer.toHex(this.cfg.baseColor);
        }
    }

    private String formatValue(Row row)
    {
        double v = row.getD.getAsDouble();
        if (row.step >= 1.0D)
        {
            return Integer.toString((int) Math.round(v));
        }
        if (row.max > 4.0D)
        {
            return String.format("%.1f", Double.valueOf(v));
        }
        if (row.max <= 1.0D)
        {
            return String.format("%.0f%%", Double.valueOf(v * 100.0D));
        }
        return String.format("%.2fs", Double.valueOf(v));
    }

    private static double clamp(double v, double min, double max)
    {
        return v < min ? min : (v > max ? max : v);
    }

    private static double roundStep(double v, double step, double min)
    {
        return Math.round((v - min) / step) * step + min;
    }

    private static float clamp(float v, float min, float max)
    {
        return v < min ? min : (v > max ? max : v);
    }

    private static String keyName(int keyCode)
    {
        if (keyCode == 0)
        {
            return "None";
        }
        String n = Keyboard.getKeyName(keyCode);
        return n == null ? Integer.toString(keyCode) : n;
    }

    private static float easeOutBack(float t)
    {
        if (t >= 1.0F)
        {
            return 1.0F;
        }
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float u = t - 1.0F;
        return 1.0F + c3 * u * u * u + c1 * u * u;
    }

    private static float easeInCubic(float t)
    {
        return t * t * t;
    }
}
