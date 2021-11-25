import java.net.UnknownHostException;
import java.time.*;
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
    private static JDA jda;

    public static void main(String[] args) throws LoginException {
        jda = JDABuilder.createDefault(DISCORDBOTTOKEN).build();
        jda.getPresence().setStatus(OnlineStatus.IDLE);
        jda.getPresence().setActivity(Activity.watching("Test"));
        jda.addEventListener(new Bot());

        jda.upsertCommand("ping", "Replies with pong!").queue();
        jda.upsertCommand("start", "Starts test loop").queue();
        jda.upsertCommand("stop", "Stops test loop").queue();
        jda.upsertCommand("addchannel", "Allows the bot to post in the channel in which the command was sent.").queue();
        jda.upsertCommand("removechannel", "Revokes the bot\\'s access to post in the channel in which the command was sent.").queue();
        jda.upsertCommand("addquery", "Adds a new query to the search list attributed to the respective Discord server.")
                .addOption(OptionType.STRING, "query-subreddit", "/addquery (query) (subreddit) - Subreddit is last space sep. keyword provided; default = all)", true).queue();
        jda.upsertCommand("removequery", "Removes a query from the search list attributed to the respective Discord server.")
                .addOption(OptionType.STRING, "query-subreddit", "/removequery (query) (subreddit) - Subreddit is last space sep. keyword provided; default = all)", true).queue();

        map = new HashMap<>();
        semaphore = new UpdateDB(REDDITUSERUSERNAME, REDDITUSERPASSWORD, REDDITBOTID, REDDITBOTSECRET, MONGOURI, jda);
        semaphore.setIndexes();
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

            EmbedBuilder embd = new EmbedBuilder();
            embd.setColor(0x33cc66)
                .setTitle("Added channel!")
                .setDescription("Added the following channel:")
                .addField("Channel", event.getChannel().getId(), false)
                .addField("Server", event.getGuild().getId(), false)
                .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                .setTimestamp(Instant.now());

            event.replyEmbeds(Arrays.asList(embd.build())).queue();
        }
        else if(event.getName().equals("removechannel")) {
            semaphore.removeChannel(event.getGuild().getId(), event.getChannel().getId());

            EmbedBuilder embd = new EmbedBuilder();
            embd.setColor(0x33cc66)
                    .setTitle("Removed channel!")
                    .setDescription("Removed the following channel:")
                    .addField("Channel", event.getChannel().getId(), false)
                    .addField("Server", event.getGuild().getId(), false)
                    .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                    .setTimestamp(Instant.now());

            event.replyEmbeds(Arrays.asList(embd.build())).queue();
        }
        else if(event.getName().equals("addquery")) {
            String[] query = event.getOption("query-subreddit").getAsString().split(" ");
            StringBuilder queryStr = new StringBuilder(), subredditStr = new StringBuilder();

            if(query.length >= 1) {
                if(query.length == 1) {
                    queryStr.append(query[0].toLowerCase());
                    subredditStr.append("all");
                }
                else {
                    for(int i = 0; i < query.length - 1; i++) {
                        queryStr.append(query[i].toLowerCase());
                    }
                    subredditStr.append(query[query.length - 1].toLowerCase());
                }
            }

            EmbedBuilder embd = new EmbedBuilder();
            if(!semaphore.addQuery(event.getGuild().getId(), queryStr.toString(), subredditStr.toString())) {
                embd.setColor(0xe74c3c)
                        .setTitle("Failed to add query...")
                        .setDescription("The following query already exists:")
                        .addField("Query", queryStr.toString(), false)
                        .addField("Subreddit", subredditStr.toString(), false)
                        .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                        .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
            else {
                embd.setColor(0x33cc66)
                        .setTitle("Added query!")
                        .setDescription("Added the following query:")
                        .addField("Query", queryStr.toString(), false)
                        .addField("Subreddit", subredditStr.toString(), false)
                        .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                        .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
        }
        else if(event.getName().equals("removequery")) {
            String[] query = event.getOption("query-subreddit").getAsString().split(" ");
            StringBuilder queryStr = new StringBuilder(), subredditStr = new StringBuilder();

            if(query.length >= 1) {
                if(query.length == 1) {
                    queryStr.append(query[0].toLowerCase());
                    subredditStr.append("all");
                }
                else {
                    for(int i = 0; i < query.length - 1; i++) {
                        queryStr.append(query[i].toLowerCase());
                    }
                    subredditStr.append(query[query.length - 1].toLowerCase());
                }
            }

            EmbedBuilder embd = new EmbedBuilder();
            if(!semaphore.removeQuery(event.getGuild().getId(), queryStr.toString(), subredditStr.toString())) {
                embd.setColor(0xe74c3c)
                    .setTitle("Failed to remove query...")
                    .setDescription("The following query does not exist in the database:")
                    .addField("Query", queryStr.toString(), false)
                    .addField("Subreddit", subredditStr.toString(), false)
                    .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                    .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
            else {
                embd.setColor(0x33cc66)
                    .setTitle("Removed query!")
                    .setDescription("Removed the following query:")
                    .addField("Query", queryStr.toString(), false)
                    .addField("Subreddit", subredditStr.toString(), false)
                    .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                    .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
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
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        semaphore.removeGuild(event.getGuild().getId());
        if(map.containsKey(event.getGuild().getId())) {
            map.get(event.getGuild().getId()).stopActive();
            map.remove(event.getGuild().getId());
        }
    }
}