package com.rem.stairwaytogodhood.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.rem.stairwaytogodhood.StairwayToGodhood;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>占位界面</b> —— 全屏黑底，中央一颗缓缓浮现、闪烁蓝紫光的「星辰」，
 * 屏幕下方随机飘过紫色的"附魔乱码"低语。
 *
 * <h2>画面构成</h2>
 * <ol>
 *     <li><b>星辰</b>（{@code no_content_star.png}）：屏幕正中，开场逐渐淡入，
 *         之后以正弦脉动"一闪一闪"，散发蓝紫光。</li>
 *     <li><b>低语</b>：屏幕下方，每轮随机挑几条短语依次出现。
 *         每个字由 <b>3 个乱码符号</b>组成 —— 它们从<b>星辰中心</b>出发，
 *         像星旋一样旋转着飞向该字的位置，落位后融合成真字。</li>
 * </ol>
 *
 * <p>所有可调项（语句池、条数、星辰大小/闪烁、低语速度/颜色/位置）都在配置文件
 * {@code config/stairway_to_godhood-client.toml} 里，见 {@link NoContentConfig}。
 *
 * <p>按 ESC 关闭，返回游戏。
 */
public class NoContentScreen extends Screen {

    private static final ResourceLocation STAR =
            new ResourceLocation(StairwayToGodhood.MOD_ID, "textures/gui/no_content_star.png");
    private static final int STAR_TEX = 512;
    /** 乱码符号池（MC 默认字体一定支持的 ASCII 符号）。 */
    private static final String GLYPHS = "/\\|<>-+=*#%&@$?!~^:;,.()[]{}0123456789";
    /** 组成一个文字的乱码符号数量。 */
    private static final int SYMBOLS_PER_CHAR = 3;
    /** 拖尾点的数量（每个符号身后拖几个渐淡的残影）。 */
    private static final int TRAIL_COUNT = 5;
    /** 拖尾点之间的时间间隔（tick）—— 间隔越大拖尾拉得越长。 */
    private static final float TRAIL_SPACING = 1.4F;

    private int ticks;
    private final Random random = new Random();
    private List<Whisper> batch = List.of();
    private int batchLength;

    /**
     * 一条低语。
     *
     * @param text      最终要拼出来的文字
     * @param startTick 相对本轮开始的起始 tick
     * @param spin      每个乱码符号的旋转圈数（长度 = text.length() * {@link #SYMBOLS_PER_CHAR}，
     *                  正负随机 —— 符号沿不同方向的螺旋飞向落点）
     */
    private record Whisper(String text, int startTick, float[] spin) {
    }

    public NoContentScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        super.init();
        ticks = 0;
        startBatch();
    }

    /** 从配置的语句池里随机挑几条，依次排好时间轴。 */
    private void startBatch() {
        String[] pool0 = NoContentConfig.whispers;
        int min = NoContentConfig.minLines;
        int max = NoContentConfig.maxLines;
        int count = min + random.nextInt(Math.max(1, max - min + 1));
        count = Math.min(count, pool0.length);

        List<String> pool = new ArrayList<>(List.of(pool0));
        List<Whisper> list = new ArrayList<>();
        int t = 20;                                   // 开场稍等一会儿再开口
        for (int i = 0; i < count; i++) {
            String text = pool.remove(random.nextInt(pool.size()));
            float[] spin = new float[text.length() * SYMBOLS_PER_CHAR];
            for (int k = 0; k < spin.length; k++) {
                spin[k] = (random.nextFloat() * 1.2F + 0.6F) * (random.nextBoolean() ? 1.0F : -1.0F);
            }
            list.add(new Whisper(text, t, spin));
            t += NoContentConfig.lineGap;
        }
        batch = list;
        batchLength = t + NoContentConfig.lineShow + 40;   // 本轮总时长（播完重新抽一批）
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (ticks >= batchLength) {
            ticks = 0;
            startBatch();
        }
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, this.width, this.height, 0xFF000000);   // 全屏黑底

        float time = ticks + partialTick;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        renderStar(gui, time);
        renderWhispers(gui);

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /** 中央的星辰：淡入 + 正弦脉动闪烁。 */
    private void renderStar(GuiGraphics gui, float time) {
        int fadeTicks = NoContentConfig.starFadeTicks;
        float fade = fadeTicks <= 0 ? 1.0F : Mth.clamp(time / fadeTicks, 0.0F, 1.0F);
        fade = fade * fade;                                     // ease-in：更"慢慢出现"
        float minAlpha = NoContentConfig.starMinAlpha;
        float pulse = minAlpha + (1.0F - minAlpha)
                * (0.5F + 0.5F * Mth.sin(time * NoContentConfig.starPulseSpeed));
        float alpha = fade * pulse;

        int size = Math.round(this.height * NoContentConfig.starSize);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        gui.blit(STAR, (this.width - size) / 2, (this.height - size) / 2, size, size,
                0.0F, 0.0F, STAR_TEX, STAR_TEX, STAR_TEX, STAR_TEX);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 屏幕下方的紫色低语：乱码从星辰中心螺旋飞出，汇聚成文字。 */
    private void renderWhispers(GuiGraphics gui) {
        int show = NoContentConfig.lineShow;
        for (Whisper w : batch) {
            int local = ticks - w.startTick();
            if (local < 0 || local >= show) {
                continue;
            }
            drawFlyingLine(gui, w, local);
        }
    }

    /**
     * 画一条低语。
     * <p>
     * 排版：整行按<b>真字宽</b>居中排布（每个字的目标位置唯一且不重叠）。
     * 每个字由 {@link #SYMBOLS_PER_CHAR} 个乱码符号组成：符号从<b>星辰中心</b>出发，
     * 沿螺旋轨迹（距离线性展开 + 角度旋转收拢）飞向该字的位置；
     * 三个符号全部落位后，融合成真字（真字淡入）。
     */
    private void drawFlyingLine(GuiGraphics gui, Whisper w, int local) {
        String text = w.text();
        int show = NoContentConfig.lineShow;
        int fly = NoContentConfig.charFly;
        float delay = NoContentConfig.charDelay;
        int fade = NoContentConfig.lineFade;
        float scaleMul = NoContentConfig.lineScale;
        int rgb = NoContentConfig.lineRgb;

        float centerX = this.width / 2.0F;               // 星辰中心 = 乱码的出生点
        float centerY = this.height / 2.0F;

        // ⭐ 等宽格子排版：中文是全角等宽字符，按"标称字宽"铺格子，每个字在格子里居中。
        //    ⛔ 不能用 font.width / lineHeight 推算 —— 在替换字体的环境（如 ModernUI）里，
        //       它们对中文的返回值远小于实际渲染宽度，字符会全部叠在一起。
        //       ⇒ 字宽改为可配置项（cellWidth），默认值贴合本机实测。
        int cellW = Math.max(2, Math.round(NoContentConfig.cellWidth));
        int totalW = cellW * text.length();
        float baseX = (this.width - totalW) / 2.0F + NoContentConfig.offsetX;   // 整行居中 + 微调
        int ty = Math.round(this.height * NoContentConfig.lineYRatio + NoContentConfig.offsetY);

        // 整行末尾淡出
        float alpha = fade <= 0 ? 1.0F : (local > show - fade ? (show - local) / (float) fade : 1.0F);
        alpha = Mth.clamp(alpha, 0.0F, 1.0F);

        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            // 该字的目标中心（未缩放坐标）：格子中心
            float px = baseX + i * cellW + cellW / 2.0F;
            float py = ty;
            // 相对星辰中心的位移向量 —— 螺旋就是把这个向量"从 0 转着展开到全长"
            float dx = px - centerX;
            float dy = py - centerY;

            float litStart = i * delay + (SYMBOLS_PER_CHAR - 1) * 2.0F + fly - 6.0F;
            float litP = Mth.clamp((local - litStart) / 8.0F, 0.0F, 1.0F);

            // ---- 3 个乱码符号：从星辰中心错落出发，沿螺旋飞向落点，身后拖残影 ----
            for (int j = 0; j < SYMBOLS_PER_CHAR; j++) {
                // 拖尾（从旧到新画，新的盖在旧的上面）：直接沿轨迹公式回溯历史时刻
                for (int k = TRAIL_COUNT; k >= 1; k--) {
                    float[] tail = symbolPos(w, i, j, local - k * TRAIL_SPACING, centerX, centerY, dx, dy);
                    if (tail == null) {
                        continue;
                    }
                    float fadeK = 1.0F - k / (float) (TRAIL_COUNT + 1);   // 越旧越淡
                    int ta = (int) (255.0F * alpha * fadeK * 0.35F);
                    if (ta <= 3) {
                        continue;
                    }
                    String shown = glyphAt(i * SYMBOLS_PER_CHAR + j, (int) (local - k * TRAIL_SPACING));
                    gui.pose().pushPose();
                    gui.pose().translate(tail[0], tail[1], 0.0F);
                    gui.pose().scale(scaleMul * 0.55F, scaleMul * 0.55F, 1.0F);
                    gui.drawString(this.font, shown, -this.font.width(shown) / 2.0F, -4,
                            (ta << 24) | rgb, false);
                    gui.pose().popPose();
                }

                // 本体
                float[] pos = symbolPos(w, i, j, local, centerX, centerY, dx, dy);
                if (pos == null) {
                    continue;                            // 还没出发 / 已落位
                }
                float e = pos[3];
                // 接近落点时符号淡出（真字在此时淡入，视觉上"融合成字"）
                float symA = 1.0F - Mth.clamp((e - 0.85F) / 0.15F, 0.0F, 1.0F);
                int a = (int) (255.0F * alpha * symA * (0.35F + 0.65F * e));
                int symColor = (a << 24) | rgb;

                String shown = glyphAt(i * SYMBOLS_PER_CHAR + j, local);
                gui.pose().pushPose();
                gui.pose().translate(pos[0], pos[1], 0.0F);
                gui.pose().scale(scaleMul * 0.8F, scaleMul * 0.8F, 1.0F);
                gui.drawString(this.font, shown, -this.font.width(shown) / 2.0F, -4, symColor, false);
                gui.pose().popPose();
            }

            // ---- 真字：三个符号落位后淡入 ----
            if (litP > 0.0F) {
                int a = (int) (255.0F * alpha * litP);
                int litColor = (a << 24) | rgb;
                gui.pose().pushPose();
                gui.pose().translate(px, py, 0.0F);
                gui.pose().scale(scaleMul, scaleMul, 1.0F);
                gui.drawString(this.font, ch, -cellW / 2.0F, -4, litColor, false);
                gui.pose().popPose();
            }
        }
    }

    /**
     * 求第 {@code (i, j)} 个乱码符号在时刻 {@code t} 的位置。
     * <p>
     * 轨迹 = 星旋：从星辰中心出发，位移向量按进度 {@code e} 展开，
     * 同时绕中心旋转（进度到 1 时角度归零、正好落在目标字位上）。
     *
     * @return {@code [x, y, p, e]}；{@code null} = 该时刻尚未出发或已落位
     */
    private float[] symbolPos(Whisper w, int i, int j, float t,
                              float centerX, float centerY, float dx, float dy) {
        float fly = NoContentConfig.charFly;
        float delay = NoContentConfig.charDelay;
        float p = (t - i * delay - j * 2.0F) / fly;
        if (p <= 0.0F || p >= 1.0F) {
            return null;
        }
        float e = p * p * (3.0F - 2.0F * p);
        float ang = w.spin()[i * SYMBOLS_PER_CHAR + j] * (1.0F - e) * Mth.TWO_PI;
        float rr = dx * e;
        float rr2 = dy * e;
        float ca = Mth.cos(ang);
        float sa = Mth.sin(ang);
        return new float[]{centerX + rr * ca - rr2 * sa, centerY + rr * sa + rr2 * ca, p, e};
    }

    /** 取第 {@code index} 个符号当前的乱码字符：稳定（不逐帧乱跳）但每 2 tick 变一次。 */
    private String glyphAt(int index, int local) {
        int k = Math.floorMod(index * 31 + local / 2, GLYPHS.length());
        return String.valueOf(GLYPHS.charAt(k));
    }

    /** 便捷入口：打开这个界面。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new NoContentScreen());
    }
}
