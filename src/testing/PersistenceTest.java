package testing;

import junit.framework.TestCase;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import protocol.K;
import protocol.V;
import server.storage.PUTStatus;
import server.storage.disk.PersistenceManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.hamcrest.CoreMatchers.equalTo;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PersistenceTest extends TestCase {
    PersistenceManager persistenceManager = new PersistenceManager();
    private final K key = new K("SomeKey=09".getBytes());
    private V value = new V("==Abc09$8".getBytes());


    @Test
    public void test1CreateFile() {
        PUTStatus status = persistenceManager.write(key.get(), value.get());
        assertThat(status, is(PUTStatus.CREATE_SUCCESS));
        Path filePath = persistenceManager.getFilePath(key.get());
        assertThat(Files.exists(filePath) && !Files.isDirectory(filePath), equalTo(Boolean.TRUE));
    }

    @Test
    public void test2ReadFile() {
        byte[] get = persistenceManager.read(key.get());
        assertThat(get, equalTo(value.get()));
    }

    @Test
    public void test3UpdateFile() {
        //Update
        String newString = "New\"Val=10";
        V newValue = new V(newString.getBytes());
        PUTStatus status = persistenceManager.write(key.get(), newValue.get());
        assertThat(status, is(PUTStatus.UPDATE_SUCCESS));

        //Read
        byte[] get = persistenceManager.read(key.get());
        assertThat(get, equalTo(newValue.get()));

    }

    @Test
    public void test4DeleteFile() {
        PUTStatus status = persistenceManager.delete(key.get());
        assertThat(status, is(PUTStatus.DELETE_SUCCESS));

        Path filePath = persistenceManager.getFilePath(key.get());
        assertThat(!Files.exists(filePath) && !Files.isDirectory(filePath), equalTo(Boolean.TRUE));
    }
}
