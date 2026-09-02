package com.example.redis.command;

import com.example.redis.command.impl.DelCommand;
import com.example.redis.command.impl.ExistsCommand;
import com.example.redis.command.impl.ExpireCommand;
import com.example.redis.command.impl.GetCommand;
import com.example.redis.command.impl.SetCommand;
import com.example.redis.command.impl.TtlCommand;
import com.example.redis.exception.InvalidCommandException;
import com.example.redis.exception.InvalidExpireTimeException;
import com.example.redis.exception.NotAnIntegerException;
import com.example.redis.exception.SyntaxErrorException;
import com.example.redis.exception.UnknownCommandException;
import com.example.redis.exception.WrongNumberOfArgumentsException;
import com.example.redis.storage.InMemoryStore;
import com.example.redis.storage.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecutorTest {

    private CommandExecutor executor;

    @BeforeEach
    void setUp() {
        Store store = new InMemoryStore();
        executor = new CommandExecutor(List.of(
                new SetCommand(store),
                new GetCommand(store),
                new DelCommand(store),
                new ExistsCommand(store),
                new ExpireCommand(store),
                new TtlCommand(store)
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
}