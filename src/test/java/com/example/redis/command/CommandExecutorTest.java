package com.example.redis.command;

import com.example.redis.command.impl.DelCommand;
import com.example.redis.command.impl.ExistsCommand;
import com.example.redis.command.impl.ExpireCommand;
import com.example.redis.command.impl.GetCommand;
import com.example.redis.command.impl.HGetAllCommand;
import com.example.redis.command.impl.HGetCommand;
import com.example.redis.command.impl.HSetCommand;
import com.example.redis.command.impl.LPushCommand;
import com.example.redis.command.impl.LRangeCommand;
import com.example.redis.command.impl.SAddCommand;
import com.example.redis.command.impl.SIsMemberCommand;
import com.example.redis.command.impl.SetCommand;
import com.example.redis.command.impl.TtlCommand;
import com.example.redis.command.impl.ZAddCommand;
import com.example.redis.command.impl.ZRangeCommand;
import com.example.redis.exception.InvalidCommandException;
import com.example.redis.exception.UnknownCommandException;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.exception.WrongTypeException;
import com.example.redis.storage.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandExecutorTest {

    private CommandExecutor executor;

    @BeforeEach
    void setUp() {
        InMemoryStore store = new InMemoryStore();
        executor = new CommandExecutor(List.of(
                new SetCommand(store),
                new GetCommand(store),
                new DelCommand(store),
                new ExistsCommand(store),
                new ExpireCommand(store),
                new TtlCommand(store),
                new LPushCommand(store),
                new LRangeCommand(store),
                new SAddCommand(store),
                new SIsMemberCommand(store),
                new HSetCommand(store),
                new HGetCommand(store),
                new HGetAllCommand(store),
                new ZAddCommand(store),
                new ZRangeCommand(store)
        ));
    }

    @Test
    void setReturnsOk() {
        assertEquals("OK", executor.execute("SET name Yash"));
    }

    @Test
    void getReturnsPreviouslySetValue() {
        executor.execute("SET name Yash");
        assertEquals("Yash", executor.execute("GET name"));
    }

    @Test
    void getOnMissingKeyReturnsNull() {
        assertNull(executor.execute("GET missing"));
    }

    @Test
    void delReturnsCountOfDeletedKeys() {
        executor.execute("SET a 1");
        executor.execute("SET b 2");
        assertEquals(2, executor.execute("DEL a b c"));
    }

    @Test
    void existsReturnsCountOfExistingKeys() {
        executor.execute("SET a 1");
        assertEquals(1, executor.execute("EXISTS a b"));
    }

    @Test
    void commandNameIsCaseInsensitive() {
        assertEquals("OK", executor.execute("set name Yash"));
        assertEquals("Yash", executor.execute("GeT name"));
    }

    @Test
    void unknownCommandThrows() {
        UnknownCommandException ex = assertThrows(UnknownCommandException.class,
                () -> executor.execute("FOO bar"));
        assertEquals("ERR unknown command 'FOO'", ex.getMessage());
    }

    @Test
    void wrongNumberOfArgumentsThrows() {
        WrongNumberOfArgumentsException ex = assertThrows(WrongNumberOfArgumentsException.class,
                () -> executor.execute("SET onlykey"));
        assertEquals("ERR wrong number of arguments for 'set' command", ex.getMessage());
    }

    @Test
    void emptyCommandThrowsInvalidCommandException() {
        assertThrows(InvalidCommandException.class, () -> executor.execute("   "));
        assertThrows(InvalidCommandException.class, () -> executor.execute(null));
    }

    @Test
    void listCommandsRoundTrip() {
        executor.execute("LPUSH mylist a b c");
        assertEquals(List.of("c", "b", "a"), executor.execute("LRANGE mylist 0 -1"));
    }

    @Test
    void setCommandsRoundTrip() {
        executor.execute("SADD tags redis java");
        assertEquals(1, executor.execute("SISMEMBER tags redis"));
        assertEquals(0, executor.execute("SISMEMBER tags missing"));
    }

    @Test
    void hashCommandsRoundTrip() {
        executor.execute("HSET user:1 name Yash");
        assertEquals("Yash", executor.execute("HGET user:1 name"));
        assertEquals(java.util.Map.of("name", "Yash"), executor.execute("HGETALL user:1"));
    }

    @Test
    void sortedSetCommandsRoundTrip() {
        executor.execute("ZADD leaderboard 10 alice");
        executor.execute("ZADD leaderboard 5 bob");
        assertEquals(List.of("bob", "alice"), executor.execute("ZRANGE leaderboard 0 -1"));
    }

    @Test
    void listCommandOnStringKeyThrowsWrongType() {
        executor.execute("SET name Yash");
        assertThrows(WrongTypeException.class, () -> executor.execute("LPUSH name value"));
    }

    @Test
    void zaddWithNonNumericScoreThrows() {
        assertThrows(com.example.redis.exception.NotAFloatException.class,
                () -> executor.execute("ZADD leaderboard notanumber alice"));
    }
}
