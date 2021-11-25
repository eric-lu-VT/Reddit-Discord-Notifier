import java.time.*;
import java.util.*;
import javax.security.auth.login.LoginException;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.internal.utils.PermissionUtil;

/**
 * Automatically detects new posts made on Reddit that match the specified
 * queries and are in the specified subreddits, and sends them to Discord.
 * @author @eric-lu-VT (Eric Lu)
 */

public class Bot extends ListenerAdapter {

    // Client secret variables
    private static final String DISCORDBOTTOKEN = System.getenv("DISCORDBOTTOKEN");         // Bot's Discord token
    private static final String REDDITBOTID = System.getenv("REDDITBOTID");                 // Reddit ID of the bot's owner
    private static final String REDDITBOTSECRET = System.getenv("REDDITBOTSECRET");         // Bot's Reddit token
    private static final String REDDITUSERUSERNAME = System.getenv("REDDITUSERUSERNAME");   // Reddit username of the bot's owner
    private static final String REDDITUSERPASSWORD = System.getenv("REDDITUSERPASSWORD");   // Reddit password of the bot's owner
    private static final String MONGOURI = System.getenv("MONGOURI");                       // Link that connects Bot to MongoDB database

    private static Map<String, GuildWorker> map;    // { GuildId -> GuildWorker }
    private static UpdateDB semaphore;              // All updates to database must be done on this object
    private static JDA jda;                         // Discord API

    /**
     * Driver; formally turns on bot & initializes slash commands.
     * @param args system stuff
     * @throws LoginException if provided DISCORDBOTTOKEN is invalid
     */
    public static void main(String[] args) throws LoginException {
        jda = JDABuilder.createDefault(DISCORDBOTTOKEN).build();
        jda.getPresence().setStatus(OnlineStatus.IDLE);
        jda.getPresence().setActivity(Activity.watching("Script not running currently"));
        jda.addEventListener(new Bot());

        jda.upsertCommand("ping", "Replies with pong!").queue();
        jda.upsertCommand("start", "Starts the primary script for detecting new Reddit posts, and posting them on Discord.").queue();
        jda.upsertCommand("stop", "Stops running the primary script.").queue();
        jda.upsertCommand("addchannel", "Allows the bot to post in the channel in which the command was sent.").queue();
        jda.upsertCommand("removechannel", "Revokes the bot's access to post in the channel in which the command was sent.").queue();
        jda.upsertCommand("addquery", "Adds a new query to the search list attributed to the respective Discord server.")
                .addOption(OptionType.STRING, "query-subreddit", "/addquery (query) (subreddit) - Subreddit is last space sep. keyword provided; default = all)", true).queue();
        jda.upsertCommand("removequery", "Removes a query from the search list attributed to the respective Discord server.")
                .addOption(OptionType.STRING, "query-subreddit", "/removequery (query) (subreddit) - Subreddit is last space sep. keyword provided; default = all)", true).queue();

        map = new HashMap<>();
        semaphore = new UpdateDB(REDDITUSERUSERNAME, REDDITUSERPASSWORD, REDDITBOTID, REDDITBOTSECRET, MONGOURI);
    }

    /**
     * Gets the singular JDA object.
     * @return the singular JDA object
     */
    public static JDA getJDA() {
        return jda;
    }

    /**
     * Gets the singular UpdateDB object.
     * @return the singular UpdateDB object
     */
    public static UpdateDB getSemaphore() {
        return semaphore;
    }

    /**
     * Processes to run when bot first connects to Discord.
     * @param event input that indicates JDA has finished loading all entities
     */
    @Override
    public void onReady(ReadyEvent event) {
        System.out.println("Bot ready");
    }

    /**
     * Processes to run when a slash command is received.
     * @param event information pertaining to a slash command usage
     */
    @Override
    public void onSlashCommand(SlashCommandEvent event) {
        if(event.getName().equals("ping")) {    // ping command
            long time = System.currentTimeMillis();
            event.reply("Pong!").setEphemeral(true) // reply or acknowledge
                    .flatMap(v ->
                            event.getHook().editOriginalFormat("Pong: %d ms", System.currentTimeMillis() - time) // then edit original
                    ).queue(); // Queue both reply and edit
        }
        else if(event.getName().equals("start")) {  // starts running search script in the corresponding guild
            if(map.containsKey(event.getGuild().getId())) {
                EmbedBuilder embd = new EmbedBuilder();
                embd.setColor(0xe74c3c)
                        .setTitle("Failed to start script...")
                        .setDescription("Script is already running.")
                        .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                        .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
            else {
                jda.getPresence().setActivity(Activity.watching("Script running currently!"));

                map.put(event.getGuild().getId(), new GuildWorker(event.getGuild().getId()));
                map.get(event.getGuild().getId()).start();

                EmbedBuilder embd = new EmbedBuilder();
                embd.setColor(0x33cc66)
                        .setTitle("Started script!")
                        .setDescription("Started running the script in the server.")
                        .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                        .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
        }
        else if(event.getName().equals("stop")) {   // stops running search script in the corresponding guild
            if(!map.containsKey(event.getGuild().getId())) {
                EmbedBuilder embd = new EmbedBuilder();
                embd.setColor(0xe74c3c)
                        .setTitle("Failed to stop script...")
                        .setDescription("Script is not running currently.")
                        .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                        .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
            else {
                jda.getPresence().setActivity(Activity.watching("Script not running currently"));

                map.get(event.getGuild().getId()).stopActive();
                map.remove(event.getGuild().getId());

                EmbedBuilder embd = new EmbedBuilder();
                embd.setColor(0x33cc66)
                        .setTitle("Stopped script!")
                        .setDescription("Stopped running the script in the server.")
                        .setAuthor(event.getMember().getEffectiveName(), event.getMember().getUser().getAvatarUrl(), event.getMember().getUser().getAvatarUrl())
                        .setTimestamp(Instant.now());

                event.replyEmbeds(Arrays.asList(embd.build())).queue();
            }
        }
        else if(event.getName().equals("addchannel")) { // add channel to corresponding guild in the database
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
        else if(event.getName().equals("removechannel")) {  // remove channel from the corresponding guild in the database
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
        else if(event.getName().equals("addquery")) { // add query to corresponding guild in the database, if it does not already exist
            // Processes which search term and subreddit to look for
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
        else if(event.getName().equals("removequery")) {    // remove query from corresponding guild in the database, if it exists
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

    /**
     * Processes to run when a new guild adds the Bot as a member.
     * @param event information pertaining the Bot being added
     */
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

    /**
     * Processes to run when a guild removes the Bot as a member.
     * @param event information pertaining to the Bot being removed
     */
    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        semaphore.removeGuild(event.getGuild().getId());
        if(map.containsKey(event.getGuild().getId())) {
            map.get(event.getGuild().getId()).stopActive();
            map.remove(event.getGuild().getId());
        }
    }
}