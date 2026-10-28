package com.fartiqs.profit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfitHudClient implements ClientModInitializer {

    // Distance from the top of the screen
    private static final int Y = 6;

    // Neon look
    private static final int NEON_RED = 0xFFFF2040;
    private static final int NEON_GREEN = 0xFF39FF14;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int BLUE = 0xFF2F80FF;
    private static final int LIVE_TEXT = 0xFFE6F0FF;
    private static final int PILL_BG = 0xF0060B2A;

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s*([kmbt])(?![a-z]))?";
    private static final Pattern BOARD_MONEY = Pattern.compile("(?i)money[^0-9]*" + NUM);
    private static final Pattern BOARD_DOLLAR = Pattern.compile("\\$\\s*" + NUM);
    private static final Pattern PAID_YOU = Pattern.compile("(?i)(\\S+)\\s+paid\\s+you\\s+\\$\\s*" + NUM);
    private static final Pattern YOU_PAID = Pattern.compile("(?i)you\\s+paid\\s+(\\S+)\\s+\\$\\s*" + NUM);

    private static final DecimalFormat FMT = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private static double chatTotal = 0;
    private static double chatSince = 0;
    private static double boardProfit = 0;
    private static Double lastBoard = null;
    private static int ticks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ProfitHudClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChat(message.getString());
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("fartiqsprofit", "profit_hud"),
                ProfitHudClient::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("profitreset").executes(ctx -> {
                    reset();
                    ctx.getSource().sendFeedback(Text.literal("Profit tracker reset."));
                    return 1;
                })));
    }

    private static void reset() {
        chatTotal = 0;
        chatSince = 0;
        boardProfit = 0;
        lastBoard = null;
        ticks = 0;
    }

    private static double parse(Matcher m, int numGroup) {
        double v = Double.parseDouble(m.group(numGroup).replace(",", ""));
        String suf = m.group(numGroup + 1);
        if (suf != null) {
            switch (suf.toLowerCase(Locale.ROOT)) {
                case "k" -> v *= 1e3;
                case "m" -> v *= 1e6;
                case "b" -> v *= 1e9;
                case "t" -> v *= 1e12;
            }
        }
        return v;
    }

    private static void onChat(String msg) {
        try {
            Matcher m = PAID_YOU.matcher(msg);
            if (m.find()) {
                double v = parse(m, 2);
                chatTotal += v;
                chatSince += v;
                return;
            }
            m = YOU_PAID.matcher(msg);
            if (m.find()) {
                double v = parse(m, 2);
                chatTotal -= v;
                chatSince -= v;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        ticks++;
        if (ticks % 10 != 0) return;

        Double board = readBoardMoney(mc);
        if (board == null) return;

        if (lastBoard == null) {
            if (ticks >= 60) lastBoard = board;
            return;
        }

        double unexplained = (board - lastBoard) - chatSince;
        if (Math.abs(unexplained) >= Math.max(1000, lastBoard * 0.05)) {
            boardProfit += unexplained;
            lastBoard = board;
            chatSince = 0;
        }
    }

    private static Double readBoardMoney(MinecraftClient mc) {
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return null;

        List<ScoreboardEntry> entries = new ArrayList<>(sb.getScoreboardEntries(obj));
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed());

        List<String> lines = new ArrayList<>();
        for (ScoreboardEntry e : entries) {
            Team team = sb.getScoreHolderTeam(e.owner());
            lines.add(Team.decorateName(team, e.name()).getString());
        }

        try {
            for (String line : lines) {
                Matcher m = BOARD_MONEY.matcher(line);
                if (m.find()) return parse(m, 1);
            }
            for (String line : lines) {
                Matcher m = BOARD_DOLLAR.matcher(line);
                if (m.find()) return parse(m, 1);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static String formatMoney(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return "$" + FMT.format(a / 1e12) + "T";
        if (a >= 1e9) return "$" + FMT.format(a / 1e9) + "B";
        if (a >= 1e6) return "$" + FMT.format(a / 1e6) + "M";
        if (a >= 1e3) return "$" + FMT.format(a / 1e3) + "K";
        return "$" + FMT.format(a);
    }

    // rounded rectangle (2px corners)
    private static void roundRect(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1 + 2, y1, x2 - 2, y2, color);
        ctx.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, color);
        ctx.fill(x1, y1 + 2, x2, y2 - 2, color);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.world == null) return;

        double profit = chatTotal + boardProfit;
        boolean up = profit > 0.0001;
        boolean down = profit < -0.0001;
        int color = up ? NEON_GREEN : (down ? NEON_RED : GRAY);

        String amount = (down ? "-" : (up ? "+" : "")) + formatMoney(profit);

        TextRenderer tr = mc.textRenderer;
        Text liveText = Text.literal("Live").formatted(Formatting.BOLD);
        Text amountText = Text.literal(amount).formatted(Formatting.BOLD);

        int liveW = tr.getWidth(liveText);
        int pipeW = tr.getWidth("|");
        int amountW = tr.getWidth(amountText);
        int arrowW = (up || down) ? 9 : 0;
        int arrowGap = arrowW > 0 ? 4 : 0;
        int gap = 5;

        int contentW = liveW + gap + pipeW + gap + arrowW + arrowGap + amountW;
        int padX = 9;
        int padY = 5;
        int w = contentW + padX * 2;
        int h = 9 + padY * 2;

        int x0 = (mc.getWindow().getScaledWidth() - w) / 2;
        int y0 = Y;
        int x1 = x0 + w;
        int y1 = y0 + h;

        // blue neon glow, border, background
        roundRect(ctx, x0 - 3, y0 - 3, x1 + 3, y1 + 3, withAlpha(BLUE, 0x18));
        roundRect(ctx, x0 - 2, y0 - 2, x1 + 2, y1 + 2, withAlpha(BLUE, 0x30));
        roundRect(ctx, x0 - 1, y0 - 1, x1 + 1, y1 + 1, withAlpha(BLUE, 0x60));
        roundRect(ctx, x0, y0, x1, y1, BLUE);
        roundRect(ctx, x0 + 1, y0 + 1, x1 - 1, y1 - 1, PILL_BG);

        int tx = x0 + padX;
        int ty = y0 + padY;

        ctx.drawText(tr, liveText, tx, ty, LIVE_TEXT, true);
        tx += liveW + gap;
        ctx.drawText(tr, "|", tx, ty, BLUE, false);
        tx += pipeW + gap;

        if (up) {
            for (int i = 0; i < 5; i++) {
                ctx.fill(tx + 4 - i, ty + 2 + i, tx + 5 + i, ty + 3 + i, color);
            }
        } else if (down) {
            for (int i = 0; i < 5; i++) {
                ctx.fill(tx + i, ty + 2 + i, tx + 9 - i, ty + 3 + i, color);
            }
        }
        tx += arrowW + arrowGap;

        // neon glow behind the amount, then the amount itself
        int glow = withAlpha(color, 0x40);
        ctx.drawText(tr, amountText, tx - 1, ty, glow, false);
        ctx.drawText(tr, amountText, tx + 1, ty, glow, false);
        ctx.drawText(tr, amountText, tx, ty - 1, glow, false);
        ctx.drawText(tr, amountText, tx, ty + 1, glow, false);
        ctx.drawText(tr, amountText, tx, ty, color, true);
    }
}
