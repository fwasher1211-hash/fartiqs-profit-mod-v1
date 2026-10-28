package com.fartiqs.profit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfitHudClient implements ClientModInitializer {

    // Distance from the top of the screen. Raise this if it overlaps a bossbar.
    private static final int Y = 4;

    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int BG = 0x90000000;

    // Reads the number after the word "Money" on the sidebar, e.g. "$ Money 1.5M"
    private static final Pattern MONEY = Pattern.compile(
            "(?i)money[^0-9]*([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s*([kmbt])(?![a-z]))?");

    private static final DecimalFormat FMT = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private static Double startBalance = null;
    private static Double currentBalance = null;
    private static int ticksSinceJoin = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ProfitHudClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("fartiqsprofit", "profit_hud"),
                ProfitHudClient::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("profitreset").executes(ctx -> {
                    startBalance = currentBalance;
                    ctx.getSource().sendFeedback(Text.literal("Profit tracker reset."));
                    return 1;
                })));
    }

    private static void reset() {
        startBalance = null;
        currentBalance = null;
        ticksSinceJoin = 0;
    }

    private static boolean isDonut(MinecraftClient mc) {
        ServerInfo info = mc.getCurrentServerEntry();
        return info != null && info.address != null && info.address.toLowerCase(Locale.ROOT).contains("donut");
    }

    private static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || !isDonut(mc)) return;
        ticksSinceJoin++;
        if (ticksSinceJoin % 10 != 0) return;

        Double bal = readMoney(mc);
        if (bal == null) return;
        currentBalance = bal;
        // wait ~3s after joining so we don't lock in a not-yet-loaded 0 balance
        if (startBalance == null && ticksSinceJoin >= 60) {
            startBalance = bal;
        }
    }

    private static Double readMoney(MinecraftClient mc) {
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return null;

        for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
            Team team = sb.getScoreHolderTeam(e.owner());
            String line = Team.decorateName(team, e.name()).getString();
            Matcher m = MONEY.matcher(line);
            if (!m.find()) continue;
            try {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                String suf = m.group(2);
                if (suf != null) {
                    switch (suf.toLowerCase(Locale.ROOT)) {
                        case "k" -> v *= 1e3;
                        case "m" -> v *= 1e6;
                        case "b" -> v *= 1e9;
                        case "t" -> v *= 1e12;
                    }
                }
                return v;
            } catch (NumberFormatException ignored) {
            }
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
        if (mc.options.hudHidden || mc.world == null || !isDonut(mc)) return;
        if (startBalance == null || currentBalance == null) return;

        double profit = currentBalance - startBalance;
        boolean up = profit > 0;
        boolean down = profit < 0;
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
