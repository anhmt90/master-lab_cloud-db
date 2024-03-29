package server.api;

import ecs.KeyHashRange;
import ecs.NodeInfo;
import management.MessageSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocol.kv.*;
import protocol.mapreduce.Utils;
import server.app.Server;
import util.FileUtils;
import util.StringUtils;
import util.Validate;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static protocol.Constants.MAX_BUFFER_LENGTH;
import static protocol.mapreduce.Utils.NODEID_KEYBYTES_SEP;
import static util.FileUtils.SEP;
import static util.StringUtils.EMPTY_STRING;
import static util.StringUtils.isEmpty;

/**
 * handles the batch data transfer process when adding or removing nodes takes places in the ring
 */
public class BatchDataTransferProcessor {
    private static Logger LOG = LogManager.getLogger(Server.SERVER_LOG);
    /**
     * the path to the folder where all index files residing in
     */
    private static final String DATA_TRANSFER_INDEX_FOLDER = FileUtils.WORKING_DIR + SEP + "dti" + SEP;

    /**
     * The socket being used to move data when adding/removing servers
     */
    private Socket moveDataSocket;
    BufferedOutputStream bos;
    BufferedInputStream bis;

    /**
     * the info of target server
     */
    NodeInfo target;

    /**
     * the database path to search for the data that needs to be transferred
     */
    String dbPath;

    /**
     * distinguishes the original file from the MR output having the same hashed key.
     * has the following format '[Job_Id].[Node_Id].' The 2 dots are mandatory.
     */
    String prefix = EMPTY_STRING;

    public BatchDataTransferProcessor(NodeInfo target, String dbPath) {
        this.target = target;
        this.dbPath = dbPath;
    }

    public BatchDataTransferProcessor(String dbPath, String prefix) {
        this(dbPath);
        setPrefix(prefix);
    }

    public BatchDataTransferProcessor(String dbPath) {
        this.target = null;
        this.dbPath = dbPath;
    }



    public void setPrefix(String prefix) {
//        Validate.isTrue(prefix.lastIndexOf(NODEID_KEYBYTES_SEP) == prefix.length() - 1, "Invalid prefix format. Prefix is " + prefix);
        this.prefix = prefix;
    }

    /**
     * starts transferring data to {@see target} server
     *
     * @param range the hashed key range of data that need to be transferred
     * @return boolean value indicating whether the transfer process ended successfully
     */
    public boolean handleTransferData(KeyHashRange range) {
        String[] indexFiles = new String[0];
        try {
            if (!FileUtils.dirExists(Paths.get(DATA_TRANSFER_INDEX_FOLDER))) {
                LOG.info("Creating folder for index files: " + DATA_TRANSFER_INDEX_FOLDER);
                Files.createDirectories(Paths.get(DATA_TRANSFER_INDEX_FOLDER));
            }

            indexFiles = indexData(range);
            LOG.info("Finish indexing, start transferring");
            return transfer(indexFiles);
        } catch (IOException ioe) {
            LOG.error(ioe);
            return false;
        } finally {
            try {
                LOG.info("Finish transferring, start cleaning up");
                cleanUp(indexFiles);
            } catch (IOException ioe) {
                LOG.error(ioe);
                return false;
            }
        }
    }

    /**
     * deletes all {@see indexfiles} and also all file references stored in those after the transfer process has ended
     *
     * @param indexFiles files that stores paths to the key-value files which have to be transferred
     */
    private void cleanUp(String[] indexFiles) throws IOException {
        if (indexFiles.length > 0) {
            for (String indexFile : indexFiles) {
                Path indexFilePath = Paths.get(indexFile);
                //TODO: remove data not in current readRange
//                for (String dataFile : Files.readAllLines(indexFilePath)) {
//                    Files.deleteIfExists(Paths.get(dataFile));
//                }
                Files.deleteIfExists(indexFilePath);
            }
        }

        Path[] indexFilesToRemove = Files.list(Paths.get(DATA_TRANSFER_INDEX_FOLDER))
                .filter(FileUtils::isFile)
                .toArray(Path[]::new);
        for (Path p : indexFilesToRemove)
            Files.deleteIfExists(p);
        Files.deleteIfExists(Paths.get(DATA_TRANSFER_INDEX_FOLDER));
    }

    /**
     * indexes all the data files in the given range by storing the paths to them in index files located in folder dti/
     *
     * @param range the range of data files which should be transferred
     * @return a list of paths to index files, based on which the relevant key-value files can be found for transferring
     */
    public String[] indexData(KeyHashRange range) {
        LOG.info("Indexing relevant data of range " + range);
        if (range.isWrappedAround()) {
            KeyHashRange leftRange = new KeyHashRange(range.getStart(), new String(new char[8]).replace("\0", "ffff"));
            KeyHashRange rightRange = new KeyHashRange(new String(new char[8]).replace("\0", "0000"), range.getEnd());
            return Stream.concat(Arrays.stream(indexData(leftRange)), Arrays.stream(indexData(rightRange)))
                    .toArray(String[]::new);
        }
        String[] start = StringUtils.splitEvery(range.getStart(), 2);
        String[] end = StringUtils.splitEvery(range.getEnd(), 2);
        String commonPrefix = StringUtils.getLongestCommonPrefix(range.getStart(), range.getEnd());

        String startDir = start[commonPrefix.length() / 2];
        String endDir = end[commonPrefix.length() / 2];

        String commonParent = commonPrefix.length() / 2 == 0 ?
                EMPTY_STRING :
                StringUtils.joinSeparated(Arrays.copyOfRange(start, 0, commonPrefix.length() / 2 - 1), SEP);

        String[] firstDiffDirs = new String[0];
        try {
            firstDiffDirs = getSortedChildDirs(commonParent);
            int lowerBound = getIndex(startDir, firstDiffDirs);
            int upperBound = getIndex(endDir, firstDiffDirs);

            int from = lowerBound < 0 ? -(lowerBound + 1) : lowerBound + 1;
            int to = upperBound < 0 ? -(upperBound + 1) : upperBound;

            String[] middleFullRangeDirs = Arrays.copyOfRange(firstDiffDirs, from, to);
            List<String> indexFiles = Arrays.stream(getFilesRecursively(commonParent, middleFullRangeDirs)).collect(Collectors.toList());

            walkStart(start, commonPrefix, firstDiffDirs, lowerBound, indexFiles);
            walkEnd(end, commonPrefix, firstDiffDirs, upperBound, indexFiles);

            String[] indexFileLocations = indexFiles.toArray(new String[indexFiles.size()]);
            LOG.info("Data indexed successfully with " + indexFileLocations.length + " index files");
            return indexFileLocations;

        } catch (IOException e) {
            LOG.error(e);
        } catch (RuntimeException e) {
            LOG.error(e);
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * walks along the lower bound of the range into directories to index relevant data files
     *
     * @param start         start of key range being split into 2-char-components
     * @param commonPrefix  the common prefix of start and end of the key range
     * @param firstDiffDirs list of all directories lying in the first directory level where the start and end differs
     * @param lowerBound    index of one of the {@param start} component.
     * @param indexFiles    list of paths to index files.
     */
    private void walkStart(String[] start,
                           String commonPrefix,
                           String[] firstDiffDirs,
                           int lowerBound,
                           List<String> indexFiles) throws IOException {
        LOG.info("walk start");
        String currDir = EMPTY_STRING;
        int i = commonPrefix.length() / 2 + 1;
        String[] directChildren = Arrays.copyOfRange(firstDiffDirs, 0, firstDiffDirs.length);
        while (lowerBound >= 0) {
            currDir = currDir + directChildren[lowerBound] + SEP;
            directChildren = getSortedChildDirs(currDir);
            if (directChildren.length == 0) {
                visitLastFile(indexFiles, currDir);
                break;
            }
            lowerBound = getIndex(start[i], directChildren);
            int from = lowerBound < 0 ? -(lowerBound + 1) : lowerBound + 1;
            String[] fullRangeDirs = Arrays.copyOfRange(directChildren, from, directChildren.length);
            String[] startIndexFiles = getFilesRecursively(currDir, fullRangeDirs);
            indexFiles.addAll(Arrays.asList(startIndexFiles));
            i++;
        }
    }

    /**
     * walks along the upper bound of the range into directories to index relevant data files
     *
     * @param end           end of key range being split into 2-char-components
     * @param commonPrefix  the common prefix of start and end of the key range
     * @param firstDiffDirs list of all directories lying in the first directory level where the start and end differs
     * @param upperBound    index of one of the {@param end} component.
     * @param indexFiles    list of paths to index files.
     */
    private void walkEnd(String[] end,
                         String commonPrefix,
                         String[] firstDiffDirs,
                         int upperBound,
                         List<String> indexFiles) throws IOException {
        LOG.info("walk end");
        String currDir = EMPTY_STRING;
        int i = commonPrefix.length() / 2 + 1;
        String[] directChildren = Arrays.copyOfRange(firstDiffDirs, 0, firstDiffDirs.length);
        while (upperBound >= 0) {
            currDir = currDir + directChildren[upperBound] + SEP;
            directChildren = getSortedChildDirs(currDir);
            if (directChildren.length == 0) {
                visitLastFile(indexFiles, currDir);
                break;
            }
            upperBound = getIndex(end[i], directChildren);
            int to = upperBound < 0 ? -(upperBound + 1) : upperBound;
            String[] fullRangeDirs = Arrays.copyOfRange(directChildren, 0, to);
            String[] endIndexFiles = getFilesRecursively(currDir, fullRangeDirs);
            indexFiles.addAll(Arrays.asList(endIndexFiles));
            i++;
        }
    }

    /**
     * handles the last files when we walk along the start and end of key range.
     * This is the file whose hashed key matches exactly the start and end of the range
     *
     * @param indexFiles list of paths to index files.
     * @param currDir    current directory that one is standing at
     * @throws IOException
     */
    private void visitLastFile(List<String> indexFiles, String currDir) throws IOException {
        String name = StringUtils.removeChar(currDir, SEP.charAt(0));
        Path indexFile = Paths.get(DATA_TRANSFER_INDEX_FOLDER + name);
        Files.deleteIfExists(indexFile);
        Files.createFile(indexFile);
        indexFile = Files.write(indexFile, (dbPath + currDir + name).getBytes(), StandardOpenOption.APPEND);
        indexFiles.add(indexFile.toString());
    }

    /**
     * gets index of a string in string array by binary search
     *
     * @param toSearch the string to search for
     * @param array    the array to search into
     * @return the index in the array or negative index if {@param toSearch} is not in the array
     */
    private int getIndex(String toSearch, String[] array) {
        return Arrays.binarySearch(array, toSearch);
    }

    /**
     * lists all direct child folder of the given current directory
     *
     * @param currDir the curreent directory that we are at
     * @return a nam list of all direct children of the given folder
     */
    private String[] getSortedChildDirs(String currDir) throws IOException {
        String[] directChildDirs = Files.list(Paths.get(dbPath + currDir))
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .toArray(String[]::new);
        Arrays.sort(directChildDirs);

        return directChildDirs;
    }

    /**
     * @param pathString
     * @param dirs
     * @return array of paths to temp files containing KV-file paths
     */
    private String[] getFilesRecursively(String pathString, String[] dirs) throws IOException {
        Path indexPath = Paths.get(DATA_TRANSFER_INDEX_FOLDER);
        Files.createDirectories(indexPath);

        String[] indexFiles = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            try {
                String indexFileName = StringUtils.removeChar(pathString, SEP.charAt(0)) + dirs[i];
                Path newIndexFile = Paths.get(indexPath.toString() + SEP + indexFileName);
                Files.deleteIfExists(newIndexFile);
                Path indexFile = Files.createFile(newIndexFile);

                Path child = Paths.get(dbPath + SEP + pathString + SEP + dirs[i] + SEP);
                Files.walkFileTree(child, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!FileUtils.isDir(file)) {
                            String fileName = file.getFileName().toString();
                            boolean emptyPrefix = StringUtils.isEmpty(prefix);
                            if ((emptyPrefix && !fileName.contains(NODEID_KEYBYTES_SEP)) || (!emptyPrefix && fileName.startsWith(prefix)) )
                                index(file, indexFile);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                indexFiles[i] = indexFile.toString();
            } catch (IOException ioe) {
                LOG.error(ioe);
                return new String[0];
            }
        }
        return indexFiles;
    }

    private void index(Path file, Path indexFile) throws IOException {
        Files.write(indexFile, (file.toString() + System.lineSeparator()).getBytes(), StandardOpenOption.APPEND);
    }

    /**
     * sends numerous PUT-requests to the target node to transfer the data
     *
     * @param indexFiles list of paths to index files.
     * @return boolean value indicating whether all PUT-requests ended successfully or not
     */
    public boolean transfer(String[] indexFiles) throws IOException {
        connect();
        try {
            for (String indexFile : indexFiles) {
                List<String> filesToMove = Files.readAllLines(Paths.get(indexFile));
                for (String file : filesToMove) {

                    if (!send(file))
                        return false;
                }
            }
            return true;

        } finally {
            disconnect();
        }
    }

    /**
     * sends a PUT-request to transfer a key-value pair
     *
     * @param file the key-value file
     * @return boolean value indicating whether the PUT-request ended successfully or not
     */
    private boolean send(String file) throws IOException {
        String fileName = Paths.get(file).getFileName().toString();
        String MRJobId = EMPTY_STRING;
        String k = EMPTY_STRING;
        if (fileName.indexOf(NODEID_KEYBYTES_SEP) >= 0) {
            String[] nameParts = fileName.split(NODEID_KEYBYTES_SEP);
            MRJobId = nameParts[0];
            k = nameParts[1];
        } else {
            k = fileName;
        }

        K key = new K(k);
        V val = new V(FileUtils.getValueBytes(file));
        Message message = new Message(IMessage.Status.PUT, key, val);

        finalizeMessage(MRJobId, message);

        byte[] toSend = MessageSerializer.serialize(message);

        try {
            bos = new BufferedOutputStream(moveDataSocket.getOutputStream());
            bos.write(toSend);
            bos.flush();
            LOG.info("sending " + toSend.length + " bytes to server");
        } catch (IOException e) {
            disconnect();
            LOG.error("Could't connect to the server. Disconnecting...\n" + e);
            return false;
        }
        IMessage response = MessageSerializer.deserialize(receive());
        if (response == null) {
            LOG.info("Received from server: null");
            return false;
        } else
            LOG.info("Received from server: " + response.toString());
        return true;
    }

    private void finalizeMessage(String MRJobId, Message message) {
        if (MRJobId.equals(EMPTY_STRING)) {
            message.setInternal();
        } else {
            message.setMRToken(MRJobId);
        }
    }


    public static String getHashedKeyFromFileName(Path path) {
        return path.getFileName().toString();
    }

    /**
     * receives data over socket
     *
     * @return the received byte array
     */
    private byte[] receive() {
        byte[] messageBuffer = new byte[MAX_BUFFER_LENGTH];
        int justRead = 0;
        byte[] res = null;
        try {
            bis = new BufferedInputStream(moveDataSocket.getInputStream());
            justRead = bis.read(messageBuffer);

            if (justRead < 0)
                return null;

            res = Arrays.copyOfRange(messageBuffer, 0, justRead);

            LOG.info("RECEIVE \t<"
                    + moveDataSocket.getInetAddress().getHostAddress() + ":"
                    + moveDataSocket.getPort() + ">: '"
                    + res.length + " bytes'");
        } catch (EOFException e) {
            LOG.error("CATCH EOFException", e);
        } catch (IOException e) {
            LOG.error(e);
        }
        return res;
    }

    /**
     * connects to remote target server
     */
    public void connect() throws IOException {
        try {
            moveDataSocket = new Socket();
            moveDataSocket.connect(new InetSocketAddress(target.getHost(), target.getPort()), 5000);
            LOG.info("CONNECTED to target server <" + target.getHost() + ":" + target.getPort() + "> for transfering batch data");
        } catch (UnknownHostException uhe) {
            LOG.error("Unknown host \n" + uhe);
            throw uhe;
        } catch (SocketTimeoutException ste) {
            LOG.error("Could not connect to server. Connection timeout. \n" + ste);
            throw ste;
        } catch (IOException ioe) {
            LOG.error("Could not connect to server. \n" + ioe);
            throw ioe;
        }

    }

    /**
     * disconnects from the remote target server
     */
    private void disconnect() {
        try {
            if (bos != null)
                bos.close();
            if (bis != null)
                bis.close();
            if (moveDataSocket != null) {
                moveDataSocket.close();
            }
            moveDataSocket = new Socket();
        } catch (IOException e) {
            LOG.error("Connection is already closed. \n" + e);
        }
    }

    /**
     * gets the path to the folder where all index files residing in
     *
     * @return the path of the folder
     */
    public static String getDataTransferIndexFolder() {
        return DATA_TRANSFER_INDEX_FOLDER;
    }
}
