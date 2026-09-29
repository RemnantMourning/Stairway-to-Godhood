package com.rem.stairwaytogodhood.client;

import com.rem.stairwaytogodhood.StairwayToGodhood;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>占位界面（暂无内容）的配置文件</b>。
 *
 * <p>生成位置：{@code config/stairway_to_godhood-client.toml}（首次启动自动生成）。
 * 改完保存后重启游戏（或执行 {@code /reload}）即可生效 —— 语句池与节奏参数都能调，
 * 不用改代码、不用重新编译。
 *
 * <h2>为什么把参数也放进来</h2>
 * 界面里的"星辰大小/闪烁快慢""低语飞入速度""语句内容"都是需要反复微调的东西，
 * 放进配置文件后可以边看边调。
 *
 * <h2>读取方式</h2>
 * 配置值在 {@link ModConfigEvent}（加载/重载）时一次性烤进本类的静态字段，
 * 渲染端每帧直接读静态字段（避免每帧读配置的开销）。
 */
@Mod.EventBusSubscriber(modid = StairwayToGodhood.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NoContentConfig {

    /** 默认的低语语句池。 */
    public static final String[] DEFAULT_WHISPERS = {
            "孩子，过来吧", "你终于来了", "抬头看看我", "别害怕",
            "再靠近一点", "我等了很久", "你听见了吗", "到我这儿来",
            "低头，看着我", "时间不多了", "别回头", "我记得你",
            "别急着走", "把手给我", "再往前一步", "我们等你很久了",
    };

    private static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> WHISPERS;
    private static final ForgeConfigSpec.IntValue MIN_LINES;
    private static final ForgeConfigSpec.IntValue MAX_LINES;
    private static final ForgeConfigSpec.DoubleValue STAR_SIZE;
    private static final ForgeConfigSpec.IntValue STAR_FADE_TICKS;
    private static final ForgeConfigSpec.DoubleValue STAR_PULSE_SPEED;
    private static final ForgeConfigSpec.DoubleValue STAR_MIN_ALPHA;
    private static final ForgeConfigSpec.IntValue LINE_SHOW;
    private static final ForgeConfigSpec.IntValue LINE_GAP;
    private static final ForgeConfigSpec.IntValue CHAR_FLY;
    private static final ForgeConfigSpec.DoubleValue CHAR_DELAY;
    private static final ForgeConfigSpec.DoubleValue CELL_WIDTH;
    private static final ForgeConfigSpec.DoubleValue OFFSET_X;
    private static final ForgeConfigSpec.DoubleValue OFFSET_Y;
    private static final ForgeConfigSpec.IntValue LINE_FADE;
    private static final ForgeConfigSpec.DoubleValue LINE_SCALE;
    private static final ForgeConfigSpec.DoubleValue LINE_Y_RATIO;
    private static final ForgeConfigSpec.ConfigValue<String> LINE_COLOR;

    // ================================================================ 缓存（渲染直接读）

    public static String[] whispers = DEFAULT_WHISPERS;
    public static int minLines = 5;
    public static int maxLines = 6;
    public static float starSize = 0.60F;
    public static int starFadeTicks = 80;
    public static float starPulseSpeed = 0.055F;
    public static float starMinAlpha = 0.42F;
    public static int lineShow = 110;
    public static int lineGap = 120;
    public static int charFly = 32;
    public static float charDelay = 3.2F;
    public static float cellWidth = 18.0F;
    public static float offsetX = 5.0F;
    public static float offsetY = 0.0F;
    public static int lineFade = 24;
    public static float lineScale = 2.2F;
    public static float lineYRatio = 0.78F;
    public static int lineRgb = 0xA855F7;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("「暂无内容」占位界面配置。改完保存后重启游戏（或 /reload）生效。")
                .push("no_content");

        WHISPERS = b.comment("低语语句池：每轮从这里随机挑几条显示（想加/改句子就改这里）")
                .defineList("whispers", List.of(DEFAULT_WHISPERS), o -> o instanceof String);

        b.comment("每轮随机出现几条低语").push("count");
        MIN_LINES = b.defineInRange("min", 5, 1, 50);
        MAX_LINES = b.defineInRange("max", 6, 1, 50);
        b.pop();

        b.comment("中央的星辰").push("star");
        STAR_SIZE = b.comment("星辰大小（相对屏幕高的比例）")
                .defineInRange("size", 0.60D, 0.05D, 3.0D);
        STAR_FADE_TICKS = b.comment("浮现时长（tick，20 tick = 1 秒）")
                .defineInRange("fadeTicks", 80, 0, 400);
        STAR_PULSE_SPEED = b.comment("闪烁角速度（越小闪得越慢；0 = 不闪）")
                .defineInRange("pulseSpeed", 0.055D, 0.0D, 1.0D);
        STAR_MIN_ALPHA = b.comment("闪烁时的最低不透明度（0.42 = 在 0.42~1.0 之间呼吸）")
                .defineInRange("minAlpha", 0.42D, 0.0D, 1.0D);
        b.pop();

        b.comment("屏幕下方的紫色低语").push("whisper");
        LINE_SHOW = b.comment("单条显示时长（tick）").defineInRange("showTicks", 110, 10, 2000);
        LINE_GAP = b.comment("相邻两条的起始间隔（tick）").defineInRange("gapTicks", 120, 1, 2000);
        CHAR_FLY = b.comment("单个字符的飞行时长（tick）").defineInRange("charFlyTicks", 32, 1, 400);
        CHAR_DELAY = b.comment("相邻字符的起飞延迟（tick）——错落飞入")
                .defineInRange("charDelay", 3.2D, 0.0D, 40.0D);
        CELL_WIDTH = b.comment("每个中文字占的格子宽度（GUI 像素）——字挤在一起就调大，太散就调小")
                .defineInRange("cellWidth", 18.0D, 2.0D, 80.0D);
        OFFSET_X = b.comment("水平位置微调（GUI 像素，正数向右）")
                .defineInRange("offsetX", 5.0D, -200.0D, 200.0D);
        OFFSET_Y = b.comment("垂直位置微调（GUI 像素，正数向下）")
                .defineInRange("offsetY", 0.0D, -200.0D, 200.0D);
        LINE_FADE = b.comment("一条的淡出时长（tick）").defineInRange("fadeTicks", 24, 0, 400);
        LINE_SCALE = b.comment("文字放大倍数").defineInRange("scale", 2.2D, 0.5D, 10.0D);
        LINE_Y_RATIO = b.comment("文字所在高度（0 = 屏幕顶、1 = 屏幕底）")
                .defineInRange("yRatio", 0.78D, 0.0D, 1.0D);
        LINE_COLOR = b.comment("文字颜色（十六进制 RRGGBB，不带 #）")
                .define("color", "A855F7");
        b.pop();

        b.pop();
        SPEC = b.build();
    }

    private NoContentConfig() {
    }

    /** 注册配置（在 mod 构造函数里调用一次）。 */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    /** 配置加载 / 重载时，把值烤进静态缓存。 */
    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    private static void bake() {
        List<? extends String> list = WHISPERS.get();
        List<String> cleaned = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                cleaned.add(s);
            }
        }
        whispers = cleaned.isEmpty() ? DEFAULT_WHISPERS : cleaned.toArray(new String[0]);

        minLines = MIN_LINES.get();
        maxLines = Math.max(minLines, MAX_LINES.get());

        starSize = STAR_SIZE.get().floatValue();
        starFadeTicks = STAR_FADE_TICKS.get();
        starPulseSpeed = STAR_PULSE_SPEED.get().floatValue();
        starMinAlpha = STAR_MIN_ALPHA.get().floatValue();

        lineShow = LINE_SHOW.get();
        lineGap = LINE_GAP.get();
        charFly = CHAR_FLY.get();
        charDelay = CHAR_DELAY.get().floatValue();
        cellWidth = CELL_WIDTH.get().floatValue();
        offsetX = OFFSET_X.get().floatValue();
        offsetY = OFFSET_Y.get().floatValue();
        lineFade = Math.min(LINE_FADE.get(), lineShow / 2);
        lineScale = LINE_SCALE.get().floatValue();
        lineYRatio = LINE_Y_RATIO.get().floatValue();

        try {
            lineRgb = Integer.parseInt(LINE_COLOR.get().replace("#", "").trim(), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            lineRgb = 0xA855F7;   // 配置写错就退回默认紫
        }
    }
}
