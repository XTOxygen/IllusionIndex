package com.endwithme.illusionindex;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

/**
 * Key bindings of IllusionIndex. The bindings themselves are persisted through
 * {@link Config} (key codes live in the cfg file), so a user configured key
 * survives restarts and is applied at construction time.
 *
 * <p>Because the binding is rebuilt while the game runs, key presses are read
 * directly from {@link Keyboard} events instead of relying on
 * {@link KeyBinding#isPressed()} polling state; {@code resetKeyBindingArrayAndHash}
 * keeps the vanilla registry in sync when a key is rebound from the GUI.
 *
 * @author EndWithMe
 */
public final class KeyBindings
{
    public static final String CATEGORY = "IllusionIndex";

    public final KeyBinding openGui;
    public final KeyBinding toggleObserve;
    public final KeyBinding toggleAfterimages;

    private final Config config;

    public KeyBindings(Config config)
    {
        this.config = config;
        this.openGui = new KeyBinding("Open IllusionIndex settings",
                config.keyOpenGui, CATEGORY);
        this.toggleObserve = new KeyBinding("Toggle observing invisible players",
                Keyboard.KEY_NONE, CATEGORY);
        this.toggleAfterimages = new KeyBinding("Toggle afterimages",
                Keyboard.KEY_NONE, CATEGORY);

        ClientRegistry.registerKeyBinding(this.openGui);
        ClientRegistry.registerKeyBinding(this.toggleObserve);
        ClientRegistry.registerKeyBinding(this.toggleAfterimages);
    }

    /**
     * Called on every key event while a world is loaded and no GUI is open.
     */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null)
        {
            return;
        }
        if (!Keyboard.getEventKeyState())
        {
            return; // release events only
        }

        int key = Keyboard.getEventKey();
        if (key != Keyboard.KEY_NONE && key == this.openGui.getKeyCode())
        {
            IllusionIndex.openConfigGui();
        }
        else if (key == this.toggleObserve.getKeyCode())
        {
            this.config.observeInvisible = !this.config.observeInvisible;
            this.config.save();
            sendToggleChat("Observe invisible players: "
                    + (this.config.observeInvisible ? "ON" : "OFF"));
        }
        else if (key == this.toggleAfterimages.getKeyCode())
        {
            this.config.afterimagesEnabled = !this.config.afterimagesEnabled;
            this.config.save();
            sendToggleChat("Afterimages: " + (this.config.afterimagesEnabled ? "ON" : "OFF"));
        }
    }

    /** Rebind the GUI key (called from the config GUI after a key press). */
    public void setOpenGuiKey(int keyCode)
    {
        this.config.keyOpenGui = keyCode;
        this.config.save();
        this.openGui.setKeyCode(keyCode);
        KeyBinding.resetKeyBindingArrayAndHash();
    }

    private static void sendToggleChat(String message)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null)
        {
            ChatComponentText text = new ChatComponentText("[IIndex]: " + message);
            text.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
            mc.thePlayer.addChatMessage(text);
        }
    }
}
