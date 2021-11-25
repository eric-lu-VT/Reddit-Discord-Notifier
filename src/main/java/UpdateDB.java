import com.mongodb.DBObject;
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

public class UpdateDB {

    private UserAgent userAgent;
    private Credentials credentials;
    private NetworkAdapter adapter;
    private RedditClient reddit;

    private MongoClient mongoClient;
    private JDA jda;

    public UpdateDB(String REDDITUSERUSERNAME, String REDDITUSERPASSWORD, String REDDITBOTID, String REDDITBOTSECRET, String MONGOURI, JDA jda) {
        userAgent = new UserAgent("bot", "bot", "v1.0", REDDITUSERUSERNAME);
        credentials = Credentials.script(REDDITUSERUSERNAME, REDDITUSERPASSWORD, REDDITBOTID, REDDITBOTSECRET);
        adapter = new OkHttpNetworkAdapter(userAgent);
        reddit = OAuthHelper.automatic(adapter, credentials);
        mongoClient = MongoClients.create(MONGOURI);
        this.jda = jda;
    }

    public synchronized void updateReddit(String guildId) {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> serverposts = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson projection = Projections.fields(Projections.include("queries", "channels"));
        serverposts.find(queryFilter).projection(projection).forEach(doc -> {
            List<Document> queries = (List) doc.get("queries");
            List<String> channels = (List<String>) doc.get("channels");

            queries.forEach(q -> {
                String query = (String) q.get("query"), subreddit = (String) q.get("subreddit");

                SearchPaginator paginator = reddit.subreddit(subreddit).search()
                        .limit(Paginator.RECOMMENDED_MAX_LIMIT)
                        .sorting(SearchSort.NEW)
                        .timePeriod(TimePeriod.HOUR)
                        .syntax(SearchPaginator.QuerySyntax.PLAIN)
                        .query(query)
                        .build();

                for(Listing<Submission> nextPage : paginator) {
                    for(Submission s : nextPage) {
                        MongoCollection<Document> redditposts = database.getCollection("redditposts");
                        FindIterable<Document> iterable = redditposts.find(Projections.fields(
                                and(eq("postId", s.getFullName()), eq("guildId", guildId))));

                        if(!iterable.iterator().hasNext()) { // if iterator is empty, then entry does not already exist
                            IndexOptions options = new IndexOptions().expireAfter(1L, TimeUnit.MINUTES);

                            redditposts.insertOne(new Document()
                                .append("_id", new ObjectId())
                                .append("postId", s.getFullName())
                                .append("subreddit", s.getSubreddit())
                                .append("url", "https://www.reddit.com" + s.getPermalink())
                                .append("date", s.getCreated())
                                .append("guildId", guildId)
                                .append("createdAt", new Date(System.currentTimeMillis()))
                                .append("expireAt", new Date(System.currentTimeMillis() + 60 * 60 * 1000))); // expire in 60 minutes

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

                                jda.getTextChannelById(channelId).sendMessage(embd.build()).queue();
                            });
                        }
                    }
                }
            });
        });
        System.out.println(guildId + " finished a search run");
    }

    public synchronized void setIndexes() {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");

        MongoCollection<Document> serverposts = database.getCollection("serverposts");
        serverposts.createIndex(Indexes.hashed("guildId"));

        MongoCollection<Document> redditposts = database.getCollection("redditposts");
        redditposts.createIndex(Indexes.compoundIndex(Indexes.descending("postId"), Indexes.ascending("guildId")));
        redditposts.createIndex(Indexes.ascending("date"),
                new IndexOptions().expireAfter(1L, TimeUnit.MINUTES));
    }

    public synchronized void addGuild(String guildID, List<String> channels) {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        collection.insertOne(new Document()
            .append("_id", new ObjectId())
            .append("guildId", guildID)
            .append("channels", channels)
            .append("queries", Arrays.asList(new Document()
                    .append("_id", new ObjectId())
                    .append("query", "afhafafajhfaj")           // TODO: figure out how to not need dummy entry here
                    .append("subreddit", "jahgajgajgajk"))));
    }

    public synchronized void removeGuild(String guildId) {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        collection.deleteOne(queryFilter);
    }

    public synchronized void addChannel(String guildId, String channelId) {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson update = Updates.push("channels", channelId);
        FindOneAndUpdateOptions updateOptions = new FindOneAndUpdateOptions().upsert(true);
        collection.findOneAndUpdate(queryFilter, update, updateOptions);
    }

    public synchronized void removeChannel(String guildId, String channelId) {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");

        Bson queryFilter = eq("guildId", guildId);
        Bson update = Updates.pull("channels", channelId);
        collection.updateOne(queryFilter, update);
    }

    public synchronized boolean addQuery(String guildId, String queryStr, String subredditStr) {
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
        return true;
    }

    public synchronized boolean removeQuery(String guildId, String queryStr, String subredditStr) {
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
        return true;
    }
}
