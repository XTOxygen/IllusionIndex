package com.endwithme.illusionindex;

import com.google.common.collect.Maps;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Renders invisible players and maintains their afterimage trails.
 *
 * <p><b>Reveal technique.</b> {@link RenderPlayerEvent.Pre} fires before any
 * vanilla GL work for a player happens, so cancelling it suppresses the skin,
 * the spectator ghost pass, armour layers, capes and name tags completely.
 * The player is then re-drawn from scratch as a flat silhouette: the vanilla
 * pose pipeline of {@code RendererLivingEntity.doRender} /
 * {@code RenderPlayer.doRender} (interpolated body/head angles, corpse
 * rotation, -1/-1/1 mirror, 0.9375 player scale, -1.5078125 model offset,
 * sneaking shifts, riding clamps, held item / bow poses) is replicated and the
 * model is rendered with a 1x1 dynamic texture tinted in the configured base
 * colour with the configured alpha - the original skin texture is never
 * sampled, so the player's skin colour can not show through.
 *
 * <p><b>Afterimages.</b> While an invisible player is loaded, a pose snapshot
 * is recorded a few times per second into a ring-buffer per player and
 * re-rendered in {@link RenderWorldLastEvent} with an age based fade. Afterimages
 * keep working when "observe invisible players" is disabled.
 *
 * <p><b>Spot alerts.</b> Invisible players inside the configured attack range
 * trigger a red/bold chat message once per (re-)entry.
 *
 * @author EndWithMe
 */
public final class RenderHandler
{
    private static final float PLAYER_SCALE = 0.9375F;
    private static final float MODEL_OFFSET = -1.5078125F;
    private static final float MODEL_SCALE = 0.0625F;
    /** GL 1.2 constant not exposed by LWJGL's GL11 class. */
    private static final int GL_RESCALE_NORMAL = 0x803A;

    private final Minecraft mc;
    private final Config config;

    /** Coloured 1x1 sprite per player (skins are never used). */
    private final Map<UUID, SolidSprite> sprites = Maps.newHashMap();
    /** Per player model, created once per skin type. */
    private final Map<UUID, ModelPlayer> models = Maps.newHashMap();
    /** Per player afterimage ring buffer (oldest first). */
    private final Map<UUID, GhostTrail> trails = Maps.newHashMap();
    /** Chat alert edge detection: UUID -> was in range last tick? */
    private final Map<UUID, Boolean> spotting = Maps.newHashMap();

    private World currentWorld;

    public RenderHandler(Config config)
    {
        this.config = config;
        this.mc = Minecraft.getMinecraft();
    }

    // ------------------------------------------------------------------
    // Event handlers
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onPlayerRenderPre(RenderPlayerEvent.Pre event)
    {
        if (!this.config.enabled || !this.config.observeInvisible)
        {
            return;
        }
        if (this.mc.thePlayer == null || this.mc.currentScreen != null || this.mc.theWorld == null)
        {
            return;
        }
        EntityPlayer player = event.entityPlayer;
        if (player == this.mc.thePlayer || player.isUser() || !player.isInvisible() || player.isDead)
        {
            return;
        }

        // Only handle players within the configured range (cheap distance guard,
        // vanilla still fires for everything in the frustum).
        double d = player.getDistanceSqToEntity(this.mc.thePlayer);
        double max = this.config.maxRenderDistance;
        if (d > max * max)
        {
            return;
        }

        // Stop the vanilla render (skin, spectator ghost, armour, cape, nametag ...).
        event.setCanceled(true);

        Pose pose = Pose.live(player, event.partialRenderTick, event.x, event.y, event.z);
        float[] rgb = this.config.baseColorF();
        this.drawSilhouette(pose, (float) this.config.opacity, rgb[0], rgb[1], rgb[2], true);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event)
    {
        if (this.mc.thePlayer == null || this.mc.currentScreen != null || this.mc.theWorld == null)
        {
            return;
        }
        // Reset state when the world changed (dimension switch / world hop).
        if (this.currentWorld != this.mc.theWorld)
        {
            this.currentWorld = this.mc.theWorld;
            this.models.clear();
            this.sprites.clear();
            this.trails.clear();
            this.spotting.clear();
        }

        if (!this.config.enabled || !this.config.afterimagesEnabled)
        {
            this.trails.clear();
            return;
        }

        float partial = event.partialTicks;
        double vx = this.mc.getRenderManager().viewerPosX;
        double vy = this.mc.getRenderManager().viewerPosY;
        double vz = this.mc.getRenderManager().viewerPosZ;
        float[] rgb = this.config.baseColorF();

        for (Object o : this.mc.theWorld.playerEntities)
        {
            if (!(o instanceof AbstractClientPlayer))
            {
                continue;
            }
            AbstractClientPlayer player = (AbstractClientPlayer) o;
            if (player == this.mc.thePlayer || player.isUser() || !player.isInvisible() || player.isDead)
            {
                continue;
            }
            double max = this.config.maxRenderDistance;
            if (player.getDistanceSqToEntity(this.mc.thePlayer) > max * max)
            {
                continue;
            }

            GhostTrail trail = this.trails.get(player.getUniqueID());
            if (trail == null)
            {
                trail = new GhostTrail(player);
                this.trails.put(player.getUniqueID(), trail);
            }
            trail.sample(player, partial);

            // Draw oldest frames first so blending stacks nicely.
            Iterator<GhostFrame> it = trail.frames.iterator();
            while (it.hasNext())
            {
                GhostFrame frame = it.next();
                float age = (float) ((System.nanoTime() - frame.recordedNanos) / 1.0e9D);
                if (age >= this.config.afterimageLinger)
                {
                    it.remove();
                    continue;
                }
                float t = 1.0F - age / (float) this.config.afterimageLinger;
                float alpha = (float) (this.config.opacity * 0.9D) * t * t;
                if (alpha <= 0.02F)
                {
                    continue;
                }
                Pose p = Pose.ghost(frame, player);
                p.x = frame.x - vx;
                p.y = frame.y - vy;
                p.z = frame.z - vz;
                if (p.sneak)
                {
                    p.y -= 0.125D; // RenderPlayer.doRender sneaking shift
                }
                this.drawSilhouette(p, alpha, rgb[0], rgb[1], rgb[2], false);
            }

            // Never exceed the configured frame count.
            while (trail.frames.size() > this.config.afterimageCount)
            {
                trail.frames.removeFirst();
            }
        }

        // Discard trails of players that became visible again or left.
        Iterator<Map.Entry<UUID, GhostTrail>> tit = this.trails.entrySet().iterator();
        while (tit.hasNext())
        {
            GhostTrail tr = tit.next().getValue();
            if (tr.frames.isEmpty() || tr.player.isDead || tr.player.worldObj != this.mc.theWorld)
            {
                tit.remove();
            }
            else if (!tr.player.isInvisible())
            {
                tr.frames.clear();
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        if (!this.config.enabled || !this.config.spotChatEnabled)
        {
            return;
        }
        if (this.mc.thePlayer == null || this.mc.theWorld == null)
        {
            return;
        }
        float rangeSq = (float) (this.config.spotRange * this.config.spotRange);
        boolean any = false;

        for (Object o : this.mc.theWorld.playerEntities)
        {
            if (!(o instanceof EntityPlayer))
            {
                continue;
            }
            EntityPlayer p = (EntityPlayer) o;
            if (p == this.mc.thePlayer || p.isUser() || p.isDead || !p.isInvisible())
            {
                continue;
            }
            any = true;
            UUID id = p.getUniqueID();
            boolean inRange = this.mc.thePlayer.getDistanceSqToEntity(p) <= rangeSq;
            Boolean was = this.spotting.get(id);

            if (inRange && (was == null || !was.booleanValue()))
            {
                // (re-)entered attack range while invisible -> announce once
                announce(p.getName());
            }
            this.spotting.put(id, Boolean.valueOf(inRange));
        }
        if (!any)
        {
            this.spotting.clear();
        }
    }

    /**
     * Sends the spot alert: red + bold, prefixed as required.
     */
    private void announce(String playerName)
    {
        ChatComponentText msg = new ChatComponentText(
                "[IIndex]:Spotted an invisible player! ID:" + playerName);
        msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED).setBold(true));
        this.mc.thePlayer.addChatMessage(msg);
    }

    // ------------------------------------------------------------------
    // The silhouette renderer
    // ------------------------------------------------------------------

    /**
     * Replicates the vanilla pose/transform pipeline and renders the player's
     * model with the configured base colour + alpha through a 1x1 texture, so
     * no skin data can leak through.
     */
    private void drawSilhouette(Pose pose, float alpha, float r, float g, float b, boolean writeDepth)
    {
        if (pose.entity == null)
        {
            return;
        }
        ModelPlayer model = this.modelFor(pose.entity);
        SolidSprite sprite = this.spriteFor(pose.entity.getUniqueID());
        sprite.set(r, g, b, alpha);

        // Snapshot GL state we modify (fixed-function has no managed stack).
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean tex = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean rescale = GL11.glIsEnabled(GL_RESCALE_NORMAL);
        int src = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int dst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);

        GlStateManager.pushMatrix();
        try
        {
            // --- renderLivingAt + RenderPlayer sneaking shift + rotateCorpse ---
            GlStateManager.translate(pose.x, pose.y, pose.z);
            GlStateManager.rotate(180.0F - pose.yawBody, 0.0F, 1.0F, 0.0F);

            if (pose.flipped)
            {
                GlStateManager.translate(0.0F, pose.entity.height + 0.1F, 0.0F);
                GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            }

            GlStateManager.enableRescaleNormal();
            GlStateManager.scale(-1.0F, -1.0F, 1.0F);       // vanilla model mirror
            GlStateManager.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE); // RenderPlayer.preRenderCallback
            GlStateManager.translate(0.0F, MODEL_OFFSET, 0.0F);

            // --- model state (mimics RenderPlayer.setModelVisibilities) ---
            model.swingProgress = pose.swing;
            model.isRiding = pose.sit;
            model.isChild = false;
            model.isSneak = pose.sneak;
            model.heldItemLeft = 0;
            model.heldItemRight = pose.heldItemRight;
            model.aimedBow = pose.aimedBow;

            // --- GL state for the flat translucent silhouette ---
            GlStateManager.enableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.depthMask(writeDepth);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(sprite.glId());

            model.setRotationAngles(pose.limbSwing, pose.limbSwingAmount, pose.ageInTicks,
                    pose.headYawDelta, pose.pitch, MODEL_SCALE, pose.entity);

            // Draw all body parts; angles were applied by setRotationAngles.
            drawModelParts(model, pose.sneak);
        }
        catch (Exception e)
        {
            // never let a rendering error take down the frame
        }
        finally
        {
            GlStateManager.disableRescaleNormal();
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            // restore GL state
            if (!blend) GlStateManager.disableBlend();
            if (!alphaTest) GlStateManager.disableAlpha();
            if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (!tex) GlStateManager.disableTexture2D();
            if (!rescale) GlStateManager.disableRescaleNormal();
            GlStateManager.tryBlendFuncSeparate(src, dst, srcA, dstA);
            GlStateManager.alphaFunc(alphaFunc, alphaRef);
        }
    }

    /**
     * Replicates ModelBiped.render + ModelPlayer.render (non-child path)
     * including the +0.2 sneaking shift of both passes, without relying on
     * any live entity state (needed for old afterimage frames).
     */
    private void drawModelParts(ModelPlayer model, boolean sneak)
    {
        float scale = MODEL_SCALE;

        // ModelBiped.render (non-child)
        GlStateManager.pushMatrix();
        if (sneak)
        {
            GlStateManager.translate(0.0F, 0.2F, 0.0F);
        }
        model.bipedHead.render(scale);
        model.bipedBody.render(scale);
        model.bipedRightArm.render(scale);
        model.bipedLeftArm.render(scale);
        model.bipedRightLeg.render(scale);
        model.bipedLeftLeg.render(scale);
        model.bipedHeadwear.render(scale);
        GlStateManager.popMatrix();

        // ModelPlayer.render - outer wear layer (second +0.2 sneaking shift)
        GlStateManager.pushMatrix();
        if (sneak)
        {
            GlStateManager.translate(0.0F, 0.2F, 0.0F);
        }
        model.bipedLeftLegwear.render(scale);
        model.bipedRightLegwear.render(scale);
        model.bipedLeftArmwear.render(scale);
        model.bipedRightArmwear.render(scale);
        model.bipedBodyWear.render(scale);
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------
    // Caches
    // ------------------------------------------------------------------

    private ModelPlayer modelFor(EntityPlayer player)
    {
        UUID id = player.getUniqueID();
        ModelPlayer model = this.models.get(id);
        if (model == null)
        {
            boolean slim = "slim".equals(getSkinType(player));
            model = new ModelPlayer(0.0F, slim);
            // full silhouette - force every cosmetic layer visible
            model.setInvisible(true);
            model.bipedHeadwear.showModel = true;
            model.bipedBodyWear.showModel = true;
            model.bipedLeftLegwear.showModel = true;
            model.bipedRightLegwear.showModel = true;
            model.bipedLeftArmwear.showModel = true;
            model.bipedRightArmwear.showModel = true;
            this.models.put(id, model);
        }
        return model;
    }

    private SolidSprite spriteFor(UUID id)
    {
        SolidSprite sprite = this.sprites.get(id);
        if (sprite == null)
        {
            sprite = new SolidSprite();
            this.sprites.put(id, sprite);
        }
        return sprite;
    }

    private static String getSkinType(EntityPlayer player)
    {
        if (player instanceof AbstractClientPlayer)
        {
            return ((AbstractClientPlayer) player).getSkinType();
        }
        return "default";
    }

    // ------------------------------------------------------------------
    // Pose snapshots
    // ------------------------------------------------------------------

    /** Fully interpolated player pose used by both live silhouettes and ghosts. */
    private static final class Pose
    {
        final EntityPlayer entity;
        double x, y, z;      // render space (live: event coords, ghosts: world - viewer)
        float yawBody;       // interpolated renderYawOffset
        float headYawDelta;  // head - body (deg), riding-clamped like vanilla
        float pitch;         // interpolated rotationPitch
        float limbSwing;
        float limbSwingAmount;
        float ageInTicks;    // ticksExisted + partial
        float swing;
        boolean sneak;
        boolean sit;
        boolean flipped;     // Dinnerbone / Grumm
        int heldItemRight;
        boolean aimedBow;

        Pose(EntityPlayer entity)
        {
            this.entity = entity;
        }

        /** Compute pose from the live entity, with render space coords. */
        static Pose live(EntityPlayer player, float partial, double rx, double ry, double rz)
        {
            Pose p = new Pose(player);
            p.x = rx;
            p.y = ry;
            p.z = rz;
            if (player.isSneaking())
            {
                p.y -= 0.125D; // RenderPlayer.doRender sneaking shift (others only)
            }
            fill(player, partial, p);
            return p;
        }

        /** Compute pose from a recorded ghost frame, world coords supplied later. */
        static Pose ghost(GhostFrame f, EntityPlayer player)
        {
            Pose p = new Pose(player);
            fill(player, f.partial, p);
            p.yawBody = f.yawBody;
            p.headYawDelta = f.headYawDelta;
            p.pitch = f.pitch;
            p.limbSwing = f.limbSwing;
            p.limbSwingAmount = f.limbSwingAmount;
            p.ageInTicks = f.ageInTicks;
            p.swing = f.swing;
            p.sneak = f.sneak;
            p.sit = f.sit;
            p.heldItemRight = f.heldItemRight;
            p.aimedBow = f.aimedBow;
            p.flipped = f.flipped;
            return p;
        }

        /** Everything except coordinates, computed exactly like vanilla does. */
        private static void fill(EntityPlayer entity, float partial, Pose p)
        {
            // body / head yaw
            float bodyYaw = interpolateRotation(entity.prevRenderYawOffset, entity.renderYawOffset, partial);
            float headYaw = interpolateRotation(entity.prevRotationYawHead, entity.rotationYawHead, partial);
            float delta = headYaw - bodyYaw;

            boolean shouldSit = entity.isRiding()
                    && entity.ridingEntity != null && entity.ridingEntity.shouldRiderSit();
            if (shouldSit && entity.ridingEntity instanceof EntityLivingBase)
            {
                EntityLivingBase rider = (EntityLivingBase) entity.ridingEntity;
                bodyYaw = interpolateRotation(rider.prevRenderYawOffset, rider.renderYawOffset, partial);
                delta = headYaw - bodyYaw;
                float f3 = MathHelper.wrapAngleTo180_float(delta);
                if (f3 < -85.0F) f3 = -85.0F;
                if (f3 >= 85.0F) f3 = 85.0F;
                bodyYaw = headYaw - f3;
                if (f3 * f3 > 2500.0F)
                {
                    bodyYaw += f3 * 0.2F;
                }
            }

            p.sit = shouldSit;
            p.yawBody = bodyYaw;
            p.headYawDelta = delta;
            p.pitch = entity.prevRotationPitch
                    + (entity.rotationPitch - entity.prevRotationPitch) * partial;
            p.ageInTicks = entity.ticksExisted + partial;
            p.swing = entity.getSwingProgress(partial);

            p.limbSwingAmount = entity.prevLimbSwingAmount
                    + (entity.limbSwingAmount - entity.prevLimbSwingAmount) * partial;
            p.limbSwing = entity.limbSwing - entity.limbSwingAmount * (1.0F - partial);
            if (p.limbSwingAmount > 1.0F)
            {
                p.limbSwingAmount = 1.0F;
            }

            p.sneak = entity.isSneaking();
            p.flipped = isUpsideDown(entity);

            // mimic RenderPlayer.setModelVisibilities (held items / bows)
            ItemStack item = entity.inventory.getCurrentItem();
            if (item != null)
            {
                p.heldItemRight = 1;
                if (entity.getItemInUseCount() > 0)
                {
                    EnumAction action = item.getItemUseAction();
                    if (action == EnumAction.BLOCK)
                    {
                        p.heldItemRight = 3;
                    }
                    else if (action == EnumAction.BOW)
                    {
                        p.aimedBow = true;
                    }
                }
            }
        }

        private static boolean isUpsideDown(EntityPlayer entity)
        {
            String s = EnumChatFormatting.getTextWithoutFormattingCodes(entity.getName());
            return s != null && (s.equals("Dinnerbone") || s.equals("Grumm"))
                    && entity.isWearing(EnumPlayerModelParts.CAPE);
        }
    }

    // ------------------------------------------------------------------
    // Afterimages
    // ------------------------------------------------------------------

    /** Ring buffer of pose snapshots for one invisible player. */
    private static final class GhostTrail
    {
        final EntityPlayer player;
        final Deque<GhostFrame> frames = new ArrayDeque<GhostFrame>();
        long lastSampleNanos;
        double lastX, lastY, lastZ;

        GhostTrail(EntityPlayer player)
        {
            this.player = player;
        }

        /** Record a frame if the player moved enough since the previous one. */
        void sample(EntityPlayer entity, float partial)
        {
            long now = System.nanoTime();
            double x = entity.prevPosX + (entity.posX - entity.prevPosX) * partial;
            double y = entity.prevPosY + (entity.posY - entity.prevPosY) * partial;
            double z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partial;

            boolean fresh = this.frames.isEmpty();
            boolean moved = (x - this.lastX) * (x - this.lastX)
                    + (y - this.lastY) * (y - this.lastY)
                    + (z - this.lastZ) * (z - this.lastZ) > 1.0E-4D;
            boolean enoughTime = (now - this.lastSampleNanos) / 1.0e9D >= 0.03D;

            if (fresh || (moved && enoughTime))
            {
                this.frames.addLast(new GhostFrame(entity, partial, now, x, y, z));
                this.lastSampleNanos = now;
                this.lastX = x;
                this.lastY = y;
                this.lastZ = z;
            }
        }
    }

    /** One recorded pose. */
    private static final class GhostFrame
    {
        final float partial;
        final long recordedNanos;
        final double x, y, z;
        final float yawBody, headYawDelta, pitch, limbSwing, limbSwingAmount, ageInTicks, swing;
        final boolean sneak, sit, flipped;
        final int heldItemRight;
        final boolean aimedBow;

        GhostFrame(EntityPlayer entity, float partial, long now, double x, double y, double z)
        {
            this.partial = partial;
            this.recordedNanos = now;
            this.x = x;
            this.y = y;
            this.z = z;

            Pose p = new Pose(entity);
            Pose.fill(entity, partial, p);
            this.yawBody = p.yawBody;
            this.headYawDelta = p.headYawDelta;
            this.pitch = p.pitch;
            this.limbSwing = p.limbSwing;
            this.limbSwingAmount = p.limbSwingAmount;
            this.ageInTicks = p.ageInTicks;
            this.swing = p.swing;
            this.sneak = p.sneak;
            this.sit = p.sit;
            this.flipped = p.flipped;
            this.heldItemRight = p.heldItemRight;
            this.aimedBow = p.aimedBow;
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static float interpolateRotation(float prev, float cur, float partial)
    {
        float f;
        for (f = cur - prev; f < -180.0F; f += 360.0F)
        {
            // wrap
        }
        while (f >= 180.0F)
        {
            f -= 360.0F;
        }
        return prev + partial * f;
    }

    /** 1x1 RGBA texture used to colour the silhouettes. */
    private static final class SolidSprite
    {
        private final DynamicTexture texture = new DynamicTexture(1, 1);
        private int lastArgb = Integer.MIN_VALUE;

        int glId()
        {
            return this.texture.getGlTextureId();
        }

        void set(float r, float g, float b, float a)
        {
            int argb = ((int) (a * 255.0F) << 24)
                    | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            if (argb != this.lastArgb)
            {
                this.lastArgb = argb;
                this.texture.getTextureData()[0] = argb;
                this.texture.updateDynamicTexture();
            }
        }

        private static int clamp255(float v)
        {
            int i = (int) (v * 255.0F);
            return i < 0 ? 0 : (i > 255 ? 255 : i);
        }
    }
}
