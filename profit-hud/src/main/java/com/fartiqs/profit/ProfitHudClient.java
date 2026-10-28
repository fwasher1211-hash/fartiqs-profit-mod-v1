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
    private static final int NEON_GREEN = 0xFF39FF14; // set to 0xFFFF2040 to make it red even when you're up
    private static final int GRAY = 0xFFAAAAAA;
    private static final int ACCENT = 0xFFFF2040;     // border, glow and divider
    private static final int LIVE_TEXT = 0xFFFF2040;
    private static final int PILL_BG = 0xF0200208;

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s*([kmbt])(?![a-z]))?";
