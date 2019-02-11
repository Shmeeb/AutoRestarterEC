package net.shmeeb.autorestarterec;

import com.google.inject.Inject;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
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
import org.spongepowered.api.text.serializer.TextSerializers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Plugin(id="autorestarterec", name="AutoRestarterEC", version = "3.0")
public class Main {
    private Date shutdownTime;
    private final List<Integer> ALERT_TIMES = new ArrayList(Arrays.asList(1, 5, 10, 30, 60, 300, 600, 1800));
    private List<Integer> done = new ArrayList<>();

    @Inject
    @DefaultConfig(sharedRoot = true)
    private Path path;
    @Inject @DefaultConfig(sharedRoot = true)
    private ConfigurationLoader<CommentedConfigurationNode> loader;
    private CommentedConfigurationNode root;
    private static Main instance;

    private String broadcastMessage, broadcastMessageLogOff, broadcastMessageKicking, kickMessage;
    private int hour, minute, second, interval;
    private boolean restarted = false;

    @Listener
    public void onStart(GameInitializationEvent e) {
        instance = this;
        Sponge.getCommandManager().register(this, delayCmd, "delay");
        setupShutdownTimeDate(0);
        startRestartTask();
        startAnnounceTask();
    }

    @Listener
    public void init(GamePreInitializationEvent e) throws IOException {
        if (!Files.exists(path)) {
            Sponge.getAssetManager().getAsset(this, "default.conf").get().copyToFile(path);
        }

        root = loader.load();
        root.getNode("time").setComment("Time: (24 hour time, see here http://www.onlineconversion.com/date_12-24_hour.htm)");

        hour = root.getNode("time").getNode("hour").getInt();
        minute = root.getNode("time").getNode("minute").getInt();
        second = root.getNode("time").getNode("second").getInt();
        interval = root.getNode("interval").getInt();

        broadcastMessage = root.getNode("messages").getNode("broadcast-message").getString();
        broadcastMessageLogOff = root.getNode("messages").getNode("broadcast-message-log-off").getString();
        broadcastMessageKicking = root.getNode("messages").getNode("kicking-all").getString();
        kickMessage = root.getNode("messages").getNode("kick-message").getString();
    }

    private CommandSpec delayCmd = CommandSpec.builder()
            .permission("autorestart.delay")
            .arguments(GenericArguments.optional(GenericArguments.integer(Text.of("minutes"))))
            .executor(new CommandExecutor() {
                @Override
                public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
                    if (args.getOne("minutes").isPresent()) {
                        int s = args.<Integer>getOne("minutes").get();
                        if (s > 0 && s <= 720) {
                            setupShutdownTimeDate(s);
                            sendMessage(src, "&aRescheduled a restart in " + s + " minutes from now");
                        } else {
                            sendMessage(src, "&cValue must be between 1 and 360");
                        }
                    } else {
                        Date now = new Date();
                        int secondsBetween = (int)((shutdownTime.getTime() - now.getTime()) / 1000L);
                        sendMessage(src, "&aNext scheduled restart happens at " + shutdownTime.toString() + " (" + secondsBetween/60 + " minutes)");
                        sendMessage(src, "&aType /delay <mins> to schedule one sooner");
                    }

                    return CommandResult.success();
                }
            }).build();

    private void startRestartTask() {
        Task.Builder taskBuilder = Task.builder().name("rebooter-restart-thread").intervalTicks(6);
        taskBuilder.execute(
                task -> {
                    Date now = new Date();
                    int secondsBetween = (int) ((shutdownTime.getTime() - now.getTime()) / 1000L);

                    if (secondsBetween <= 1 && !restarted) {
                        restarted = true;

                        System.out.println("[AutoRestarter] Kicking all players");
                        Sponge.getCommandManager().process(Sponge.getServer().getConsole(), "kickall " + kickMessage);

                        System.out.println("[AutoRestarter] Running /stop");
                        Sponge.getCommandManager().process(Sponge.getServer().getConsole(), "stop");
                    }
                }
        ).submit(instance);
    }

    private void startAnnounceTask() {
        Task.Builder taskBuilder = Task.builder().name("rebooter-announce-thread").intervalTicks(6);
        taskBuilder.execute(
                task -> {
                    attemptAnnounce();
                }
        ).submit(instance);
    }

    private void attemptAnnounce() {
        Date now = new Date();
        int secondsBetween = (int)((shutdownTime.getTime() - now.getTime()) / 1000L);
//        if (secondsBetween % 20 == 0) {
//            System.out.println("time till restart: " + secondsBetween);
//        }

        if (ALERT_TIMES.contains(secondsBetween)) {
            if (!done.contains(secondsBetween)) {
                done.add(secondsBetween);
                String timeFormat;

                //1 minute or more
                if (secondsBetween >= 60) {
                    if (secondsBetween == 60) {
                        timeFormat = secondsBetween / 60 + " minute";
                        Sponge.getServer().getBroadcastChannel().send(TextSerializers.FORMATTING_CODE.deserialize(color(broadcastMessage.replace("{TIME}", timeFormat))));

//                        String string = Main.announceMsg.replace("%player%", player.getName());
//                        Text text = TextSerializers.FORMATTING_CODE.deserialize(Utils.color(string));
//                        Sponge.getServer().getBroadcastChannel().send(text);

                    } else {
                        timeFormat = secondsBetween / 60 + " minutes";
                        Sponge.getServer().getBroadcastChannel().send(TextSerializers.FORMATTING_CODE.deserialize(color(broadcastMessage.replace("{TIME}", timeFormat))));
                    }

                    //less than 1 minute
                } else {
                    if (secondsBetween <= 5) {
                        if (secondsBetween == 1) {
                            timeFormat = secondsBetween + " second";
                            Sponge.getServer().getBroadcastChannel().send(TextSerializers.FORMATTING_CODE.deserialize(color(broadcastMessageLogOff.replace("{TIME}", timeFormat))));
                            Sponge.getServer().getBroadcastChannel().send(TextSerializers.FORMATTING_CODE.deserialize(color(broadcastMessageKicking)));
                        } else {
                            timeFormat = secondsBetween + " seconds";
                            Sponge.getServer().getBroadcastChannel().send(TextSerializers.FORMATTING_CODE.deserialize(color(broadcastMessageLogOff.replace("{TIME}", timeFormat))));
                        }
                    } else {
                        timeFormat = secondsBetween + " seconds";
                        Sponge.getServer().getBroadcastChannel().send(TextSerializers.FORMATTING_CODE.deserialize(color(broadcastMessage.replace("{TIME}", timeFormat))));
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void setupShutdownTimeDate(int minutes) {
        done.clear();
        if (minutes != 0) {
            Date now = new Date();
            Date future = new Date();

            future.setHours(now.getHours());
            future.setMinutes(now.getMinutes() + minutes);
            future.setSeconds(now.getSeconds());
            shutdownTime = future;
        } else {
            Date base = new Date();
            Date now = new Date();

            base.setHours(hour);
            base.setMinutes(minute);
            base.setSeconds(second);

            if (now.getHours() < 8) {
                base = addDays(base, -1);
            }

            //min supported interval is 3h
            for (int i = 1; i <= 8; i++) {
                Date attempt = new Date(base.getTime());

                attempt.setHours(base.getHours() + (interval * i));

                int minsBetween = (int)((attempt.getTime() - now.getTime()) / 1000L) / 60;

//                System.out.println(attempt.toString() + " = " + minsBetween + " mins from the attempted reboot time");

                //should be pos if attempt is in the future
                //neg if attempt is in the past

                if (minsBetween >= 30) {
//                    System.out.println("selected " + attempt.toString());
                    shutdownTime = attempt;
                    return;
                }
            }

            shutdownTime = base;
        }
    }

    private Date addDays(Date date, int days) {
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTime(date);
        cal.add(Calendar.DATE, days);

        return cal.getTime();
    }

    private static String color(String string) {
        return TextSerializers.FORMATTING_CODE.serialize(Text.of(string));
    }

    private static void sendMessage(CommandSource sender, String message) {
        if (sender == null) { return; }
        sender.sendMessage(TextSerializers.FORMATTING_CODE.deserialize(color(message)));
    }
}