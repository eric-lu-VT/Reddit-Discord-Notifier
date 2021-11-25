import com.mongodb.client.*;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.*;
import com.mongodb.client.model.*;

import net.dean.jraw.RedditClient;
import net.dean.jraw.http.NetworkAdapter;
import net.dean.jraw.http.OkHttpNetworkAdapter;
import net.dean.jraw.http.UserAgent;
import net.dean.jraw.models.Listing;
import net.dean.jraw.models.SearchSort;
import net.dean.jraw.models.Submission;
import net.dean.jraw.models.TimePeriod;
import net.dean.jraw.oauth.Credentials;
import net.dean.jraw.oauth.OAuthHelper;
import net.dean.jraw.pagination.DefaultPaginator;
import net.dean.jraw.pagination.Paginator;
import net.dean.jraw.pagination.SearchPaginator;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates reading/writing to the database.
 * (NOTE: This class is NOT written as a library; all the methods are non-static.
 * This is because concurrency/semaphore methods such as wait() are non-static.
 * Still, there should only be one of these objects in existence at one time.)
 * @author @eric-lu-VT (Eric Lu)
 */
public class UpdateDB {

    // Stuff for connecting to Reddit API
    private UserAgent userAgent;
    private Credentials credentials;
    private NetworkAdapter adapter;
    private RedditClient reddit;

    private MongoClient mongoClient; // Connects to MongoDB API

    private boolean updateRedditLock; // lock for updateReddit(...) method
    private boolean otherLock;        /* lock for all other read/write methods in the class
                                         (No need for special locks for each method; MongoDB automatically
                                         handles it for multiple concurrent operations to a single document
                                         - see https://docs.mongodb.com/manual/core/write-operations-atomicity/) */

    /**
     * UpdateDB constructor
     * @param REDDITUSERUSERNAME Reddit username of the bot's owner
     * @param REDDITUSERPASSWORD Reddit password of the bot's owner
     * @param REDDITBOTID Reddit ID of the bot's owner
     * @param REDDITBOTSECRET Bot's Reddit token
     * @param MONGOURI Link that connects Bot to MongoDB database
     */
    public UpdateDB(String REDDITUSERUSERNAME, String REDDITUSERPASSWORD, String REDDITBOTID, String REDDITBOTSECRET, String MONGOURI) {
        userAgent = new UserAgent("bot", "bot", "v1.0", REDDITUSERUSERNAME);
        credentials = Credentials.script(REDDITUSERUSERNAME, REDDITUSERPASSWORD, REDDITBOTID, REDDITBOTSECRET);
        adapter = new OkHttpNetworkAdapter(userAgent);
        reddit = OAuthHelper.automatic(adapter, credentials);
        mongoClient = MongoClients.create(MONGOURI);
        updateRedditLock = false;
        otherLock = false;
        setIndexes();
    }

    /**
     * For the given Discord guild, search for the queries attributed to said guild,
     * and post the results to each elligible channel in the guild.
     * @param guildId unique ID of guild in question
     */
    public synchronized void updateReddit(String guildId) {
        takeUpdateRedditLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> serverposts = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson projection = Projections.fields(Projections.include("queries", "channels"));

        // Get the information pertaining to the requested guildId
        serverposts.find(queryFilter).projection(projection).forEach(doc -> {
            List<Document> queries = (List) doc.get("queries");
            List<String> channels = (List<String>) doc.get("channels");

            // For all queries attributed to the given server, search each one on Reddit and get results
            queries.forEach(q -> {
                String query = (String) q.get("query"), subreddit = (String) q.get("subreddit");

                SearchPaginator paginator = reddit.subreddit(subreddit).search()
                        .limit(Paginator.RECOMMENDED_MAX_LIMIT)
                        .sorting(SearchSort.NEW)
                        .timePeriod(TimePeriod.HOUR)
                        .syntax(SearchPaginator.QuerySyntax.PLAIN)
                        .query(query)
                        .build();

                // double for loop here = for each query result from the Reddit search
                for(Listing<Submission> nextPage : paginator) {
                    for(Submission s : nextPage) {
                        // Check database if the query has been searched for, and from the current server.
                        MongoCollection<Document> redditposts = database.getCollection("redditposts");
                        FindIterable<Document> iterable = redditposts.find(Projections.fields(
                                and(eq("postId", s.getFullName()), eq("guildId", guildId))));

                        if(!iterable.iterator().hasNext()) { // if iterator is empty, then entry does not already exist
                            // Send Reddit search information to database
                            redditposts.insertOne(new Document()
                                .append("_id", new ObjectId())
                                .append("postId", s.getFullName())
                                .append("subreddit", s.getSubreddit())
                                .append("url", "https://www.reddit.com" + s.getPermalink())
                                .append("date", s.getCreated())
                                .append("guildId", guildId)
                                .append("createdAt", new Date(System.currentTimeMillis()))
                                .append("expireAt", new Date(System.currentTimeMillis() + 60 * 60 * 1000))); // expire in 60 minutes

                            // For each elligible channel in the Discord server, send query results
                            channels.forEach(channelId -> {
                                String title = s.getTitle();
                                if(title.length() > 253) { // For Reddit posts, max character length = 256
                                    title = title.substring(0, 253) + "...";
                                }
                                String authorURL = "https://www.reddit.com/u/" + URLEncoder.encode(s.getAuthor(), StandardCharsets.UTF_8);
                                String titleURL = "https://www.reddit.com" + s.getPermalink();

                                EmbedBuilder embd = new EmbedBuilder();
                                embd.setColor(0xffa500)
                                    .setAuthor(s.getAuthor(), authorURL)
                                    .setTitle(title, titleURL)
                                    .setDescription(s.getScore() + " votes and " + s.getCommentCount() + " comments so far")
                                    .setFooter("On r/" + s.getSubreddit())
                                    .setTimestamp(Instant.ofEpochMilli(s.getCreated().getTime()));

                                Bot.getJDA().getTextChannelById(channelId).sendMessage(embd.build()).queue();
                            });
                        }
                    }
                }
            });
        });

        System.out.println(guildId + " finished a search run");
        releaseUpdateRedditLock();
    }

    /**
     * Sets the indexes for the documents in the database.
     * This ensures faster/better time complexities for database search queries.
     */
    private synchronized void setIndexes() {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");

        MongoCollection<Document> serverposts = database.getCollection("serverposts");
        serverposts.createIndex(Indexes.hashed("guildId"));

        MongoCollection<Document> redditposts = database.getCollection("redditposts");
        redditposts.createIndex(Indexes.compoundIndex(Indexes.descending("postId"), Indexes.ascending("guildId")));
        redditposts.createIndex(Indexes.ascending("date"),
                new IndexOptions().expireAfter(2L, TimeUnit.HOURS)); // can change based on testing

        releaseOtherLock();
    }

    /**
     * Adds a new guild to the database.
     * @param guildId unique id of new guild
     * @param channels a list of ids pertaining to channels in the guild that are eligible for the Bot to access
     */
    public synchronized void addGuild(String guildId, List<String> channels) {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        collection.insertOne(new Document()
            .append("_id", new ObjectId())
            .append("guildId", guildId)
            .append("channels", channels)
            .append("queries", Arrays.asList(new Document()
                    .append("_id", new ObjectId())
                    .append("query", "afhafafajhfaj")           // TODO: figure out how to not need dummy entry here
                    .append("subreddit", "jahgajgajgajk"))));

        releaseOtherLock();
    }

    /**
     * Removes a guild form the database.
     * @param guildId unique id of guild to remove
     */
    public synchronized void removeGuild(String guildId) {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        collection.deleteOne(queryFilter);

        releaseOtherLock();
    }

    /**
     * Adds a new channel to the database.
     * @param guildId id of guild the channel is in
     * @param channelId id of channel to add
     */
    public synchronized void addChannel(String guildId, String channelId) {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson update = Updates.push("channels", channelId);
        FindOneAndUpdateOptions updateOptions = new FindOneAndUpdateOptions().upsert(true);
        collection.findOneAndUpdate(queryFilter, update, updateOptions);

        releaseOtherLock();
    }

    /**
     * Removes a channel from the database.
     * @param guildId id of guild the channel is in
     * @param channelId id of channel to remove
     */
    public synchronized void removeChannel(String guildId, String channelId) {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson update = Updates.pull("channels", channelId);
        collection.updateOne(queryFilter, update);

        releaseOtherLock();
    }

    /**
     * Adds a new query to a corresponding guild in the database, if it does not already exist.
     * @param guildId id of guild to attribute query to
     * @param queryStr query to search for
     * @param subredditStr subreddit to search query under
     * @return true if query add was successful (ie, the query does not already exist in the database); false otherwise
     */
    public synchronized boolean addQuery(String guildId, String queryStr, String subredditStr) {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson projection = Projections.fields( elemMatch("queries", and(eq("query", queryStr), eq("subreddit", subredditStr)))); // Add Projections
        FindIterable<Document> iterable = collection.find(queryFilter).filter(projection);

        if(iterable.iterator().hasNext()) return false; // already has query to add

        Bson update = Updates.push("queries", new Document()
                .append("_id", new ObjectId())
                .append("query", queryStr)
                .append("subreddit", subredditStr));
        FindOneAndUpdateOptions updateOptions = new FindOneAndUpdateOptions().upsert(true);
        collection.findOneAndUpdate(queryFilter, update, updateOptions);

        releaseOtherLock();
        return true;
    }

    /**
     * Removes a query from a corresponding guild in the database, if it exists.
     * @param guildId id of guild to remove query from
     * @param queryStr query to search for
     * @param subredditStr subreddit to search query under
     * @return true if query add was successful (ie, the query exists in the database); false otherwise
     */
    public synchronized boolean removeQuery(String guildId, String queryStr, String subredditStr) {
        takeOtherLock();

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson projection = Projections.fields( elemMatch("queries", and(eq("query", queryStr), eq("subreddit", subredditStr)))); // Add Projections
        FindIterable<Document> iterable = collection.find(queryFilter).filter(projection);
        if(!iterable.iterator().hasNext()) return false; // does not have query to remove

        Bson fields = new Document().append("queries", new Document().append("query", queryStr)
                                                                     .append("subreddit", subredditStr));
        Bson update = new Document("$pull", fields);
        collection.updateOne(queryFilter, update);

        releaseOtherLock();
        return true;
    }

    /**
     * Sets updateRedditLock.
     */
    private synchronized void takeUpdateRedditLock() {
        while(updateRedditLock && otherLock) {
            try {
                wait();
            }
            catch(InterruptedException e) {
                // do nothing; smother
            }
        }
        updateRedditLock = true;
    }

    /**
     * Releases updateRedditLock.
     */
    private synchronized void releaseUpdateRedditLock() {
        updateRedditLock = false;
        notifyAll();
    }

    /**
     * Sets otherLock.
     */
    private synchronized void takeOtherLock()  {
        while(updateRedditLock) {
            try {
                wait();
            }
            catch(InterruptedException e) {
                // do nothing; smother
            }
        }
        otherLock = true;
    }

    /**
     * Releases otherLock.
     */
    private synchronized void releaseOtherLock() {
        otherLock = false;
        notifyAll();
    }
}