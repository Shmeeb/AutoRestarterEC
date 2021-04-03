package net.shmeeb.autorestarterec;

import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.google.inject.CreationException;
import com.google.inject.Inject;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.text.serializer.TextSerializers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;

@Plugin(id="autorestarterec", name="AutoRestarterEC", version = "4.0")
public class Main {
    @Inject
    @DefaultConfig(sharedRoot = true)
    public Path path;

    @Inject
    @DefaultConfig(sharedRoot = true)
    public ConfigurationLoader<CommentedConfigurationNode> loader;

    public CommentedConfigurationNode root;
    public static Main instance;

    public static boolean restarted, cleared_bingo = false;
    public static boolean delay_clearing_bingo = true;
    public static boolean saved = false;
    private Logger logger = LoggerFactory.getLogger("AutoRestarterEC");

    public static List<Long> done = new ArrayList<>();
    public static List<LocalTime> reboot_times = new ArrayList<>();
    public static LocalTime reboot_time = null;
    public static String serverName, broadcastMsg, logOffMsg, kickingMsg, kickMsg;

    @Listener
    public void onStart(GameInitializationEvent e) throws IOException {
        logger.info("enabling AutoRestarterEC...");
        instance = this;

        if (!Files.exists(path))
            Sponge.getAssetManager().getAsset(this, "default.conf").get().copyToFile(path);

        loader = HoconConfigurationLoader.builder().setPath(path).build();
        root = loader.load();
        root.getNode("times").setComment("Time: (24 hour time, see here http://www.onlineconversion.com/date_12-24_hour.htm)");
        serverName = root.getNode("server-name").getString("?");

        broadcastMsg = "&e[" + serverName + "] &aServer rebooting in {TIME}!";
        logOffMsg = "&e[" + serverName + "] &aServer rebooting in {TIME}! Please log off now!";
        kickingMsg = "&e[" + serverName + "] &aKicking all players...";
        kickMsg = "Server rebooting, we'll be back in about a minute!";

        try {
            for (String t : root.getNode("times").getList(TypeToken.of(String.class))) {
                String[] arr = t.split(":");
                LocalTime time = LocalTime.of(Integer.parseInt(arr[0]), Integer.parseInt(arr[1]), 0);

                reboot_times.add(time);
            }
        } catch (ObjectMappingException ex) {
            ex.printStackTrace();

//            reboot_times.addAll(Lists.newArrayList(
//                    LocalTime.of(7, 0),
//                    LocalTime.of(13, 0),
//                    LocalTime.of(21, 0)
//            ));
        }

        for (LocalTime time : reboot_times) {
            if (time.isBefore(getNow())) continue;
            if (getNow().until(time, ChronoUnit.MINUTES) <= 60) continue;

            reboot_time = time;
            break;
        }

        if (reboot_time == null)
            reboot_time = reboot_times.get(0);

        if (reboot_time == null)
            logger.info("failed to schedule an auto reboot!");
        else
            logger.info("selected " + reboot_time.toString() + " PST as the next scheduled reboot");

        Sponge.getCommandManager().register(this, delayCmd, "delay");
        startRestartTask();

    }

    public static LocalTime getNow() {
        return LocalTime.now();
//        return LocalTime.of(6, 45, 0, 0);
    }

    public static long getSecondsUntilReboot() {
        long seconds =  getNow().until(reboot_time, ChronoUnit.SECONDS);

        if (seconds > 0) return seconds;

        seconds = getNow().until(LocalTime.MAX, ChronoUnit.SECONDS);
        seconds += LocalTime.MIDNIGHT.until(reboot_time, ChronoUnit.SECONDS);

        return seconds;
    }

    private void startRestartTask() {
        Task.builder().intervalTicks(10).execute(task -> {
            long seconds = getSecondsUntilReboot();

            //                                          10m,  30m
            if (Arrays.asList(1L, 10L, 30L, 60L, 120L, 600L, 1800L).contains(seconds)) {
                if (done.contains(seconds)) return;
                done.add(seconds);
                String timeFormat;

                //1 minute or more
                if (seconds >= 60) {
                    if (seconds == 60) {
                        timeFormat = seconds / 60 + " minute";
                        MessageChannel.TO_ALL.send(getText(broadcastMsg.replace("{TIME}", timeFormat)));
                    } else {
                        timeFormat = seconds / 60 + " minutes";
                        MessageChannel.TO_ALL.send(getText(broadcastMsg.replace("{TIME}", timeFormat)));

                        if ((seconds / 60) == 30) 
                            Sponge.getCommandManager().process(Sponge.getServer().getConsole(), "boss delay");
                    }

                    //less than 1 minute
                } else {
                    if (seconds <= 5) {
                        if (seconds == 1) {
                            timeFormat = seconds + " second";

                            MessageChannel.TO_ALL.send(getText(logOffMsg.replace("{TIME}", timeFormat)));
                            MessageChannel.TO_ALL.send(getText(kickingMsg));
                        } else {
                            timeFormat = seconds + " seconds";

                            MessageChannel.TO_ALL.send(getText(logOffMsg.replace("{TIME}", timeFormat)));
                        }
                    } else {
                        timeFormat = seconds + " seconds";

                        MessageChannel.TO_ALL.send(getText(broadcastMsg.replace("{TIME}", timeFormat)));
                    }
                }
            }

            if (seconds <= 6) {
                logger.info(seconds + " until reboot");
            }

            if (seconds <= 5 && delay_clearing_bingo && !cleared_bingo) {
                cleared_bingo = true;
                logger.info("Running /bingo --reset");
                Sponge.getCommandManager().process(Sponge.getServer().getConsole(), "bingo --reset");
            }

//            if (seconds <= 4 && !saved) {
//                saved = true;
//                logger.info("Running /save-all");
//                Sponge.getCommandManager().process(Sponge.getServer().getConsole(), "save-all");
//            }

            if (seconds <= 3 && !restarted) {
                restarted = true;
                logger.info("Running /stop");
                Sponge.getCommandManager().process(Sponge.getServer().getConsole(), "stop");
            }
        }).submit(instance);
    }

    public static CommandSpec delayCmd = CommandSpec.builder()
            .permission("autorestart.delay")
            .arguments(
                    GenericArguments.optional(GenericArguments.integer(Text.of("minutes"))),
                    GenericArguments.optional(GenericArguments.bool(Text.of("clear bingo?")))
            ).executor((src, args) -> {
                if (args.getOne("minutes").isPresent()) {
                    int mins = args.<Integer>getOne("minutes").get();

                    if (args.getOne("clear bingo?").isPresent() && args.<Boolean>getOne("clear bingo?").get()) {
                        delay_clearing_bingo = true;
                    } else {
                        delay_clearing_bingo = false;
                    }

                    reboot_time = getNow().plus(mins, ChronoUnit.MINUTES);
                    done.clear();

                    sendMessage(src, "&aRescheduled a restart in " + mins + " minutes from now. " + (delay_clearing_bingo ? "Bingo will be cleared." : "Bingo will not be cleared."));
                } else {
                    long seconds = getSecondsUntilReboot();

                    sendMessage(src, "&aNext scheduled restart happens at " + reboot_time.toString() + " PST (" + seconds / 60 + " minutes)");
                    sendMessage(src, "&aType /delay <mins> to schedule one sooner");
                }

                return CommandResult.success();
            }).build();

    private static void sendMessage(CommandSource sender, String message) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(getText(message));
    }

    public static Text getText(String message) {
        return TextSerializers.FORMATTING_CODE.deserialize(color(message));
    }

    public static String color(String string) {
        return TextSerializers.FORMATTING_CODE.serialize(Text.of(string));
    }
}