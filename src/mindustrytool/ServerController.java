package mindustrytool;

import arc.*;
import arc.util.*;
import arc.util.CommandHandler.*;
import mindustry.Vars;
import mindustry.core.*;
import mindustry.core.GameState.State;
import mindustry.game.*;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.*;
import mindustry.maps.*;
import mindustry.maps.Maps.*;
import mindustry.mod.Mods.*;
import mindustry.net.Administration.Config;
import mindustry.net.Packets.KickReason;
import mindustrytool.commands.ServerCommands;
import mindustrytool.handlers.VoteHandler;
import java.time.format.*;

import static mindustrytool.MindustryToolPlugin.*;

public class ServerController implements ApplicationListener {
    protected static DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    public static ServerController instance;

    public static final CommandHandler handler = new CommandHandler("");
    public static final VoteHandler voteHandler = new VoteHandler();

    public static volatile boolean autoPaused = false;
    public static Gamemode lastMode;
    public static boolean inGameOverWait = false;
    private int serverMaxTps = 60;

    public ServerController() {
        instance = this;
        setup();

    }

    protected void setup() {
        Core.settings.defaults("bans", "", "admins", "", "shufflemode", "custom", "globalrules",
                "{reactorExplosions: false, logicUnitBuild: false}");

        ServerCommands.registerCommands(handler);

        registerHandler();

        try {
            lastMode = Gamemode.valueOf(Core.settings.getString("lastServerMode", "survival"));
        } catch (Exception event) {
            lastMode = Gamemode.survival;
        }

        Time.setDeltaProvider(() -> Core.graphics.getDeltaTime() * serverMaxTps);

        if (!Vars.mods.orderedMods().isEmpty()) {
            Log.info("@ mods loaded.", Vars.mods.orderedMods().size);
        }

        int unsupported = Vars.mods.list().count(mod -> !mod.enabled());

        if (unsupported > 0) {
            Log.err("There were errors loading @ mod(s):", unsupported);
            for (LoadedMod mod : Vars.mods.list().select(mod -> !mod.enabled())) {
                Log.err("- @ &ly(@)", mod.state, mod.meta.name);
            }
        }

        if (Version.build == -1) {
            Log.warn("&lyYour server is running a custom build, which means that client checking is disabled.");
            Log.warn(
                    "&lyIt is highly advised to specify which version you're using by building with gradle args &lb&fb-Pbuildversion=&lr<build>");
        }

        try {
            Vars.maps.setShuffleMode(ShuffleMode.valueOf(Core.settings.getString("shufflemode")));
        } catch (Exception event) {
            Vars.maps.setShuffleMode(ShuffleMode.all);
        }

        Events.on(EventType.PlayerLeave.class, event -> {
            Player player = event.player;
            voteHandler.removeVote(player);
        });

        Events.on(PlayerChatEvent.class, event -> {
            Player player = event.player;
            String message = event.message;

            String chat = Strings.format("[@] => @", player.plainName(), message);

            apiGateway.emit("CHAT_MESSAGE", chat);
        });

        Events.on(PlayerJoin.class, event -> {
            if (Vars.state.isPaused() && autoPaused) {
                Vars.state.set(State.playing);
                autoPaused = false;
            }

            String playerName = event.player != null ? event.player.plainName() : "Unknown";
            String chat = Strings.format("@ joined the server, current players: @", playerName, Groups.player.size());

            apiGateway.emit("CHAT_MESSAGE", chat);
        });

        Events.on(PlayerLeave.class, event -> {
            if (!Vars.state.isPaused() && Groups.player.size() == 1) {
                Vars.state.set(State.paused);
                autoPaused = true;
            }

            String playerName = event.player != null ? event.player.plainName() : "Unknown";
            String chat = Strings.format("@ leaved the server, current players: @", playerName,
                    Groups.player.size() - 1);

            apiGateway.emit("CHAT_MESSAGE", chat);
        });

        Events.on(GameOverEvent.class, event -> {

            String message = Vars.state.rules.waves
                    ? Strings.format("Game over! Reached wave @ with @ players online on map @.", Vars.state.wave,
                            Groups.player.size(), Strings.capitalize(Vars.state.map.plainName()))
                    : Strings.format("Game over! Team @ is victorious with @ players online on map @.",
                            event.winner.name, Groups.player.size(), Strings.capitalize(Vars.state.map.plainName()));

            apiGateway.emit("CHAT_MESSAGE", message);

            Map map = Vars.maps.getNextMap(lastMode, Vars.state.map);
            if (map != null) {

                String winnerMessage = Vars.state.rules.pvp
                        ? Strings.format("[accent]The @ team is victorious![]", event.winner.coloredName())
                        : "[scarlet]Game over![]";

                String nextMapMessage = "\nNext selected map: [accent]" + map.name() + "[white]"
                        + (map.hasTag("author") ? " by[accent] " + map.author() + "[white]" : "") + "."
                        + "\nNew game begins in " + Config.roundExtraTime.num() + " seconds.";

                apiGateway.emit("CHAT_MESSAGE", winnerMessage + nextMapMessage);
            }
        });
    }

    public void registerHandler() {
        apiGateway.on("DISCORD_MESSAGE", String.class, event -> Call.sendMessage(event.getPayload()));
    }

    public void handleCommandString(String line) {
        CommandResponse response = handler.handleMessage(line);

        if (response.type == ResponseType.unknownCommand) {

            int minDst = 0;
            Command closest = null;

            for (Command command : handler.getCommandList()) {
                int dst = Strings.levenshtein(command.text, response.runCommand);
                if (dst < 3 && (closest == null || dst < minDst)) {
                    minDst = dst;
                    closest = command;
                }
            }

            if (closest != null && !closest.text.equals("yes")) {
                Log.err("Command not found. Did you mean \"" + closest.text + "\"?");
            } else {
                Log.err("Invalid command. Type 'help' for help.");
            }
        } else if (response.type == ResponseType.fewArguments) {
            Log.err("Too few command arguments. Usage: " + response.command.text + " " + response.command.paramText);
        } else if (response.type == ResponseType.manyArguments) {
            Log.err("Too many command arguments. Usage: " + response.command.text + " " + response.command.paramText);
        }
    }

    public void setNextMapOverride(Map map) {
        Vars.maps.setNextMapOverride(map);
    }
}
