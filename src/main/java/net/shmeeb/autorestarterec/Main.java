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

@Plugin(id="autorestarterec", name="AutoRestarterEC", version = "4.0")
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
    private int hour1, hour2, minute1, minute2, second1, second2;
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
        root.getNode("times").setComment("Time: (24 hour time, see here http://www.onlineconversion.com/date_12-24_hour.htm)");

        hour1 = root.getNode("times").getNode("one").getNode("hour").getInt();
        minute1 = root.getNode("times").getNode("one").getNode("minute").getInt();
        second1 = root.getNode("times").getNode("one").getNode("second").getInt();

        hour2 = root.getNode("times").getNode("two").getNode("hour").getInt();
        minute2 = root.getNode("times").getNode("two").getNode("minute").getInt();
        second2 = root.getNode("times").getNode("two").getNode("second").getInt();

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
            Date future1 = new Date();
            Date future2 = new Date();
            Date now = new Date();

            future1.setHours(hour1);
            future1.setMinutes(minute1);
            future1.setSeconds(second1);

            future2.setHours(hour2);
            future2.setMinutes(minute2);
            future2.setSeconds(second2);

//            outputDebug(now, future1, future2);

            long f1diff = future1.getTime() - now.getTime();
            long f2diff = future2.getTime() - now.getTime();

            if (f1diff > 0 && f2diff > 0 && f2diff > f1diff) { //both restarts are in the future
//                System.out.println("one is up next");
                shutdownTime = future1;
            } else if (f1diff < 0 && f2diff > 0 && f2diff > f1diff) { //in the middle of both restarts
//                System.out.println("two is up next");
                shutdownTime = future2;
            } else if (f1diff < 0 && f2diff < 0) { //after both restarts
//                System.out.println("one is up next (next day)");
                Date nextDay = addDays(future1, 1);
//                System.out.println(nextDay.toString());
                shutdownTime = nextDay;
            }
        }
    }

    private void outputDebug(Date now, Date future1, Date future2) {
        System.out.println("------------------------------------------------");

        System.out.println("timestamps:");
        System.out.println("future1 debug: " + future1.toString());
        System.out.println("future2 debug: " + future2.toString());

        System.out.println("differences:");
        int f1secondsBetween = (int) ((future1.getTime() - now.getTime()) / 1000L);
        int f2secondsBetween = (int) ((future2.getTime() - now.getTime()) / 1000L);

        if (f1secondsBetween > f2secondsBetween) {
            System.out.println("f1 - now = " + String.valueOf(f1secondsBetween) + "s (greater)");
            System.out.println("f2 - now = " + String.valueOf(f2secondsBetween) + "s");
        } else {
            System.out.println("f1 - now = " + String.valueOf(f1secondsBetween) + "s");
            System.out.println("f2 - now = " + String.valueOf(f2secondsBetween) + "s (greater)");
        }

        long f1diff = future1.getTime() - now.getTime();
        long f2diff = future2.getTime() - now.getTime();

        if (f1diff > 0 && f2diff > 0 && f2diff > f1diff) {
            System.out.println("one is up next");
        } else if (f1diff < 0 && f2diff > 0 && f2diff > f1diff) { //in the middle of both restarts
            System.out.println("two is up next");
        } else if (f1diff < 0 && f2diff < 0) {
            System.out.println("one is up next (next day)");
        }

        System.out.println("------------------------------------------------");
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