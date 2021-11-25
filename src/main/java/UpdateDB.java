import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
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
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.*;

public class UpdateDB {

    private UserAgent userAgent;
    private Credentials credentials;
    private NetworkAdapter adapter;
    private RedditClient reddit;

    private MongoClient mongoClient;

    public UpdateDB(String REDDITUSERUSERNAME, String REDDITUSERPASSWORD, String REDDITBOTID, String REDDITBOTSECRET, String MONGOURI) {
        userAgent = new UserAgent("bot", "bot", "v1.0", REDDITUSERUSERNAME);
        credentials = Credentials.script(REDDITUSERUSERNAME, REDDITUSERPASSWORD, REDDITBOTID, REDDITBOTSECRET);
        adapter = new OkHttpNetworkAdapter(userAgent);
        reddit = OAuthHelper.automatic(adapter, credentials);
        mongoClient = MongoClients.create(MONGOURI);

        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");
        MongoCollection<Document> collection = database.getCollection("serverposts");
        Document doc = collection.find(eq("_id", "713927595644682290")).first();
        // System.out.println(doc.toJson());
    }

    public synchronized void updateReddit(String guildId) {
        SearchPaginator paginator = reddit.subreddit("nfl").search()
                .limit(Paginator.RECOMMENDED_MAX_LIMIT)
                .sorting(SearchSort.NEW)
                .timePeriod(TimePeriod.DAY)
                .syntax(SearchPaginator.QuerySyntax.PLAIN)
                .query("griffen")
                .build();

        Iterator<Listing<Submission>> it = paginator.iterator();
        List<Listing> list = new ArrayList<>();
        while(it.hasNext()) {
            list.add(it.next());
        }
    }

    public synchronized void setIndexes() {
        MongoDatabase database = mongoClient.getDatabase("reddit-scrape");

        MongoCollection<Document> collection1 = database.getCollection("serverposts");
        collection1.createIndex(Indexes.hashed("guildId"));

        MongoCollection<Document> collection2 = database.getCollection("redditposts");
        collection2.createIndex(Indexes.compoundIndex(Indexes.descending("postId"), Indexes.ascending("guildId")));
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
                    .append("query", "afhafafajhfaj")
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

    public boolean addQuery(String guildId, String queryStr, String subredditStr) {
        return false;
    }

    public boolean removeQuery(String guildId, String queryStr, String subredditStr) {
        return false;
    }
}
