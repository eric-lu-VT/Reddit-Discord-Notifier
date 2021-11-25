import java.net.UnknownHostException;
import java.util.*;
import javax.security.auth.login.LoginException;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.utils.Compression;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.internal.utils.PermissionUtil;
import org.bson.Document;

public class Bot extends ListenerAdapter {

    private static final String DISCORDBOTTOKEN = System.getenv("DISCORDBOTTOKEN");
    private static final String DISCORDID = System.getenv("DISCORDID");
    private static final String USERAGENT = "Whatever";
    private static final String REDDITBOTID = System.getenv("REDDITBOTID");
    private static final String REDDITBOTSECRET = System.getenv("REDDITBOTSECRET");
    private static final String REDDITUSERUSERNAME = System.getenv("REDDITUSERUSERNAME");
    private static final String REDDITUSERPASSWORD = System.getenv("REDDITUSERPASSWORD");
    private static final String MONGOURI = System.getenv("MONGOURI");

    private static Map<String, GuildWorker> map;
    private static UpdateDB semaphore;

    public static void main(String[] args) throws LoginException, InterruptedException, UnknownHostException {
        map = new HashMap<>();
        semaphore = new UpdateDB(REDDITUSERUSERNAME, REDDITUSERPASSWORD, REDDITBOTID, REDDITBOTSECRET, MONGOURI);
        semaphore.setIndexes();

        JDA jda = JDABuilder.createDefault(DISCORDBOTTOKEN).build();
        jda.getPresence().setStatus(OnlineStatus.IDLE);
        jda.getPresence().setActivity(Activity.watching("Test"));
        jda.addEventListener(new Bot());

        jda.upsertCommand("ping", "Replies with pong!").queue();
        jda.upsertCommand("start", "Starts test loop").queue();
        jda.upsertCommand("stop", "Stops test loop").queue();
        jda.upsertCommand("addchannel", "Allows the bot to post in the channel in which the command was sent.").queue();
        jda.upsertCommand("removechannel", "Revokes the bot\\'s access to post in the channel in which the command was sent.").queue();
        jda.upsertCommand("addquery", "Adds a new query to the search list attributed to the respective Discord server.")
                .addOption(OptionType.USER, "res", "/addquery [query] [subreddit] (Subreddit is last space separated keyword provided; default = all)", true).queue();
        jda.upsertCommand("removequery", "Removes a query from the search list attributed to the respective Discord server.")
                .addOption(OptionType.USER, "res", "/removequery [query] [subreddit] (Subreddit is last space separated keyword provided; default = all)", true).queue();
    }

    @Override
    public void onReady(ReadyEvent event) {
        System.out.println("Bot ready");
    }

    @Override
    public void onSlashCommand(SlashCommandEvent event) {
        if(event.getName().equals("ping")) {
            long time = System.currentTimeMillis();
            event.reply("Pong!").setEphemeral(true) // reply or acknowledge
                    .flatMap(v ->
                            event.getHook().editOriginalFormat("Pong: %d ms", System.currentTimeMillis() - time) // then edit original
                    ).queue(); // Queue both reply and edit
        }
        else if(event.getName().equals("start")) {
            if(map.containsKey(event.getGuild().getId())) {
                event.reply("Script already running!").queue();
            }
            else {
                map.put(event.getGuild().getId(), new GuildWorker(event.getGuild().getId(), semaphore));
                map.get(event.getGuild().getId()).start();
                event.reply("Starting script").queue();
            }
        }
        else if(event.getName().equals("stop")) {
            if(!map.containsKey(event.getGuild().getId())) {
                event.reply("Script already stopped!").queue();
            }
            else {
                map.get(event.getGuild().getId()).stopActive();
                map.remove(event.getGuild().getId());
                event.reply("Stopping script").queue();
            }
        }
        else if(event.getName().equals("addchannel")) {
            semaphore.addChannel(event.getGuild().getId(), event.getChannel().getId());
            event.reply("Added channel " + event.getChannel().getId()).queue();
        }
        else if(event.getName().equals("removechannel")) {
            semaphore.removeChannel(event.getGuild().getId(), event.getChannel().getId());
            event.reply("Removed channel " + event.getChannel().getId()).queue();
        }
        else if(event.getName().equals("addquery")) {
            System.out.println(event.getOption("res").getAsString());
        }
        else if(event.getName().equals("removequery")) {
            System.out.println(event.getOption("res").getAsString());
        }
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        List<String> channels = new ArrayList<>();
        for(GuildChannel c : event.getGuild().getChannels()) {
            if(c.getType().equals(ChannelType.TEXT)
                && PermissionUtil.checkPermission(c, event.getGuild().getSelfMember(), Permission.VIEW_CHANNEL)
                && PermissionUtil.checkPermission(c, event.getGuild().getSelfMember(), Permission.MESSAGE_READ)
                && PermissionUtil.checkPermission(c, event.getGuild().getSelfMember(), Permission.MESSAGE_WRITE))
            {
                channels.add(c.getId());
            }
        }
        semaphore.addGuild(event.getGuild().getId(), channels);

        System.out.println("Guild joined: " + event.getGuild());
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        semaphore.removeGuild(event.getGuild().getId());
        if(map.containsKey(event.getGuild().getId())) {
            map.get(event.getGuild().getId()).stopActive();
            map.remove(event.getGuild().getId());
        }

        System.out.println("Guild leave: " + event.getGuild());
    }
}