# Reddit-Discord-Notifier

Automatically detects new posts made on Reddit that match the specified queries and are in the specified subreddits, and sends them to Discord.

## Impressions

![img](https://i.imgur.com/KQ1hzg6.png)

## Overview
This bot primarily uses the Discord API (through [JDA](https://github.com/DV8FromTheWorld/JDA)) and the Reddit API (through [JRAW](https://github.com/mattbdean/JRAW)), in conjuction with a MongoDB for the backend (through the [MongoDB Java Driver](https://docs.mongodb.com/drivers/java/sync/current/)) The database set up by this project uses hashed and compound indexes when appropriate, ensuring ```O(log(n))``` performance for queries. 

This is a rewrite of an [old version](https://github.com/eric-lu-VT/DEPRECATED-Reddit-Discord-Alert) of this project. Porting over from JavaScript to Java was necessary for multithreading capabilities, which allow for individual servers to run the primary script concurrently with one another. It also allows for each individual server to control ```/start``` and ```/stop``` of their respective scripts.

Here is a pseudocode outline of how the bot works:
- On login, initialize commands to Discord API.
- When a Discord server requests to start script (```/start```)
  - Every 30 seconds until script told to stop (```/stop```, or server removes Bot while script is in action), do the following:
    - For all queries attributed to the given server, search each one on Reddit and get results
      - For each result from the Reddit search, check database if results has been searched for, and from the current server.
        - If yes, do nothing (if the database has the entry, it means it has been searched for from the current server already)
        - If no, send the query to Discord, and send the query to the database with an expiration date of two hours
- Constantly listen for other commands/events
    - If user runs comamand ( ```/ping``` or ```/addchannel``` or ```/removechannel``` or  ```/addquery [query] [subreddit]``` or ```/removequery [query] [subreddit]```), respond appropriately
    - If Bot is added to new server, add the corresponding server info to the database
    - If Bot is removed from a server, remove the corresponding server info from the database
- For read/write requests to database, keep operations in sync with ```updateRedditLock``` and ```otherLock```.
  - ```updateRedditLock``` is a lock for the ```updateReddit(...)``` method, while ```otherLock``` is a lock for all the other read/write methods. 
    - No need for special locks for each method; MongoDB automatically handles it for multiple concurrent operations to a single document, as described [here](https://docs.mongodb.com/manual/core/write-operations-atomicity/).

### Commands
- ```/ping```: Replies with pong!
- ```/start```: Starts the primary script for detecting new Reddit posts, and posting them on Discord.
- ```/stop```: Stops running the primary script.
- ```/addchannel```: Allows the bot to post in the channel in which the command was sent.
- ```/removechannel```: Revokes the bot's access to post in the channel in which the command was sent.
- ```/addquery [query] [subreddit]```: Adds a new query (search term, subreddit) to the search list attributed to the respective Discord server, if such an entry **does not** already exist. (Subreddit is last space separated keyword provided; defaults to "all" if only one space separated keyword provided.)
- ```/removequery [query] [subreddit]```: "Removes a query (search term, subreddit) from the search list attributed to the respective Discord server, if such an entry **does** already exist. (Subreddit is last space separated keyword provided; defaults to "all" if only one space separated keyword provided.)

## Public Version Installation
[Click here](https://discord.com/api/oauth2/authorize?client_id=912892084875321356&permissions=2147568640&scope=bot%20applications.commands) to invite the bot to your server.
- Bot has the following permissions:
    - ```Send Messages```
    - ```Read Message History```
    - ```Use Slash Commands```
    - ```Read Messages/View Channels```
    - ```Embed Links```

## Self-Hosting Installation
[See here](https://github.com/eric-lu-VT/Reddit-Discord-Notifier/wiki) for instructions on how to self-host this bot.

## Roadmap
- Add POJOs (basically the way to implement schemas for Java MongoDB)
- Use an actual ```.config``` file or ```.env``` variables (the fake ```Config.java``` class doesn't really count)
  - For whatever reason, the Java boilerplate, ```.jar``` stuff, and the ubuntu terminal for hosting makes it really hard to set either of the two approaches up
