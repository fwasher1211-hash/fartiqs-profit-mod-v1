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

    private static final int Y = 4;

    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int BG = 0x90000000;

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s*([kmbt])(?![a-z]))?";
    private static final Pattern BOARD_MONEY = Pattern.compile("(?i)money[^0-9]*" + NUM);
    private static final Pattern BOARD_DOLLAR = Pattern.compile("\\$\\s*" + NUM);
    private static final Pattern PAID_YOU = Pattern.compile("(?i)(\\S+)\\s+paid\\s+you\\s+\\$\\s*" + NUM);
    private static final Pattern YOU_PAID = Pattern.compile("(?i)you\\s+paid\\s+(\\S+)\\s+\\$\\s*" + NUM);

    private static final DecimalFormat FMT = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    // exact profit from /pay messages
    private static double chatTotal = 0;
    // chat amounts since the scoreboard last moved (so big payments aren't counted twice)
    private static double chatSince = 0;
    // profit from big scoreboard changes (sales, auctions, etc.)
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
                    chatTotal = 0;
                    chatSince = 0;
                    boardProfit = 0;
                    lastBoard = null;
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
            if (ticks >= 60) lastBoard = board; // wait for scoreboard to load
            return;
        }

        // The sidebar rounds (e.g. 8B), so only trust big moves (5%+ of balance)
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

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.world == null) return;

        double profit = chatTotal + boardProfit;
        boolean up = profit > 0.0001;
        boolean down = profit < -0.0001;
        int color = up ? GREEN : (down ? RED : GRAY);

        String text = formatMoney(profit);
        TextRenderer tr = mc.textRenderer;
        int textW = tr.getWidth(text);
        int arrowW = (up || down) ? 9 : 0;
        int gap = arrowW > 0 ? 4 : 0;
        int total = arrowW + gap + textW;

        int x = (mc.getWindow().getScaledWidth() - total) / 2;
        int y = Y;

        ctx.fill(x - 5, y - 3, x + total + 5, y + 11, BG);

        if (up) {
            for (int i = 0; i < 5; i++) {
                ctx.fill(x + 4 - i, y + 2 + i, x + 5 + i, y + 3 + i, color);
            }
        } else if (down) {
            for (int i = 0; i < 5; i++) {
                ctx.fill(x + i, y + 2 + i, x + 9 - i, y + 3 + i, color);
            }
        }

        ctx.drawText(tr, text, x + arrowW + gap, y, color, true);
    }
}
