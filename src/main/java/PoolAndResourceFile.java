import java.io.*;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.microsoft.azure.storage.*;

import com.microsoft.azure.storage.blob.*;

import utils.StorageService;

import com.microsoft.azure.batch.*;
import com.microsoft.azure.batch.auth.*;
import com.microsoft.azure.batch.protocol.models.*;

public class PoolAndResourceFile {

    // Get Batch and storage account information from environment
    static String BATCH_ACCOUNT = "azurebatchpoc";
    static String BATCH_ACCESS_KEY = "PmWnRbeONXqgkCudDA1JKSnUrhHAGu7XmXvmoRy0suOL/lw2KABzYUVb8hHFyaZ8e7OA8SYO7Ynf+ABanSsE7g==";
    static String BATCH_URI = "https://azurebatchpoc.uksouth.batch.azure.com";
    static String STORAGE_ACCOUNT_NAME = "samplepoc2022";
    static String STORAGE_ACCOUNT_KEY = "gF/wMAN3w28a/HBI8hU6PRb00mHb+gUzWOLyae8n5Ss34P9rsrfgg/vaY/+aan0/QhtY/pVMDsUT+AStqT2Jog==";
    static String STORAGE_CONTAINER_NAME = "azure-batch-storage";

    // How many tasks to run across how many nodes
    static int TASK_COUNT = 5;
    static int NODE_COUNT = 1;
    private static StorageService storageSvc= new StorageService();

    // Modify these values to change which resources are deleted after the job finishes.
    // Skipping pool deletion will greatly speed up subsequent runs
    static boolean CLEANUP_STORAGE_CONTAINER = false;
    static boolean CLEANUP_JOB = true;
    static boolean CLEANUP_POOL = true;

    public static void main(String[] argv) throws Exception {
        BatchClient client = BatchClient.open(new BatchSharedKeyCredentials(BATCH_URI, BATCH_ACCOUNT, BATCH_ACCESS_KEY));
        CloudBlobContainer container = storageSvc.createBlobContainerIfNotExists(STORAGE_ACCOUNT_NAME, STORAGE_ACCOUNT_KEY, STORAGE_CONTAINER_NAME);

        String userName = "azbatchpoc";
        String poolId = userName + "-pooltest";
        String jobId = "PoolAndResourceFileJob-" + userName + "-" +
                new Date().toString().replaceAll("(\\.|:|\\s)", "-");

        try {
            CloudPool sharedPool = createPoolIfNotExists(client, poolId);

            // Submit a job and wait for completion
            submitJob(client, container, sharedPool.id(), jobId, TASK_COUNT);
            waitForTasksToComplete(client, jobId, Duration.ofMinutes(5));

            System.out.println("\nTask Results");
            System.out.println("------------------------------------------------------");

            List<CloudTask> tasks = client.taskOperations().listTasks(jobId);
            for (CloudTask task : tasks) {
                if (task.executionInfo().failureInfo() != null) {
                    System.out.println("Task " + task.id() + " failed: " + task.executionInfo().failureInfo().message());
                }

                String outputFileName = task.executionInfo().exitCode() == 0 ? "stdout.txt" : "stderr.txt";
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                client.fileOperations().getFileFromTask(jobId, task.id(), outputFileName, stream);
                String fileContent = stream.toString("UTF-8");

                System.out.println("\nTask " + task.id() + " output (" + outputFileName + "):");
                System.out.println(fileContent);
            }

            System.out.println("------------------------------------------------------\n");
        } catch (BatchErrorException err) {
            printBatchException(err);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            // Clean up resources
            if (CLEANUP_JOB) {
                try {
                    System.out.println("Deleting job " + jobId);
                    client.jobOperations().deleteJob(jobId);
                } catch (BatchErrorException err) {
                    printBatchException(err);
                }
            }
            if (CLEANUP_POOL) {
                try {
                    System.out.println("Deleting pool " + poolId);
                    client.poolOperations().deletePool(poolId);
                } catch (BatchErrorException err) {
                    printBatchException(err);
                }
            }
            if (CLEANUP_STORAGE_CONTAINER) {
                System.out.println("Deleting storage container " + container.getName());
                container.deleteIfExists();
            }
        }

        System.out.println("\nFinished");
        System.exit(0);
    }

    /**
     * Create a pool if one doesn't already exist with the given ID
     *
     * @param client  The Batch client
     * @param poolId  The ID of the pool to create or look up
     *
     * @return  A newly created or existing pool
     */
    private static CloudPool createPoolIfNotExists(BatchClient client, String poolId)
            throws BatchErrorException, IllegalArgumentException, IOException, InterruptedException, TimeoutException {
        // Create a pool with 1 A1 VM
        String osPublisher = "OpenLogic";
        String osOffer = "CentOS";
        String poolVMSize = "Standard_A1_v2";
        int poolVMCount = 1;
        Duration poolSteadyTimeout = Duration.ofMinutes(5);
        Duration vmReadyTimeout = Duration.ofMinutes(20);

        // If the pool exists and is active (not being deleted), resize it
        if (client.poolOperations().existsPool(poolId) && client.poolOperations().getPool(poolId).state().equals(PoolState.ACTIVE)) {
            System.out.println("Pool " + poolId + " already exists: Resizing to " + poolVMCount + " dedicated node(s)");
            client.poolOperations().resizePool(poolId, NODE_COUNT, 0);
        } else {
            System.out.println("Creating pool " + poolId + " with " + poolVMCount + " dedicated node(s)");

            // See detail of creating IaaS pool at
            // https://blogs.technet.microsoft.com/windowshpc/2016/03/29/introducing-linux-support-on-azure-batch/
            // Get the sku image reference
            List<ImageInformation> skus = client.accountOperations().listSupportedImages();
            String skuId = null;
            ImageReference imageRef = null;

            for (ImageInformation sku : skus) {
                if (sku.osType() == OSType.LINUX) {
                    if (sku.verificationType() == VerificationType.VERIFIED) {
                        if (sku.imageReference().publisher().equalsIgnoreCase(osPublisher)
                                && sku.imageReference().offer().equalsIgnoreCase(osOffer)) {
                            imageRef = sku.imageReference();
                            skuId = sku.nodeAgentSKUId();
                            break;
                        }
                    }
                }
            }

            // Use IaaS VM with Linux
            VirtualMachineConfiguration configuration = new VirtualMachineConfiguration();
            configuration.withNodeAgentSKUId(skuId).withImageReference(imageRef);

            client.poolOperations().createPool(poolId, poolVMSize, configuration, poolVMCount);
        }

        long startTime = System.currentTimeMillis();
        long elapsedTime = 0L;
        boolean steady = false;

        // Wait for the VM to be allocated
        System.out.print("Waiting for pool to resize.");
        while (elapsedTime < poolSteadyTimeout.toMillis()) {
            CloudPool pool = client.poolOperations().getPool(poolId);
            if (pool.allocationState() == AllocationState.STEADY) {
                steady = true;
                break;
            }
            System.out.print(".");
            TimeUnit.SECONDS.sleep(10);
            elapsedTime = (new Date()).getTime() - startTime;
        }
        System.out.println();

        if (!steady) {
            throw new TimeoutException("The pool did not reach a steady state in the allotted time");
        }

        // The VMs in the pool don't need to be in and IDLE state in order to submit a
        // job.
        // The following code is just an example of how to poll for the VM state
        startTime = System.currentTimeMillis();
        elapsedTime = 0L;
        boolean hasIdleVM = false;

        // Wait for at least 1 VM to reach the IDLE state
        System.out.print("Waiting for VMs to start.");
        while (elapsedTime < vmReadyTimeout.toMillis()) {
            List<ComputeNode> nodeCollection = client.computeNodeOperations().listComputeNodes(poolId,
                    new DetailLevel.Builder().withSelectClause("id, state").withFilterClause("state eq 'idle'")
                            .build());
            if (!nodeCollection.isEmpty()) {
                hasIdleVM = true;
                break;
            }

            System.out.print(".");
            TimeUnit.SECONDS.sleep(10);
            elapsedTime = (new Date()).getTime() - startTime;
        }
        System.out.println();

        if (!hasIdleVM) {
            throw new TimeoutException("The node did not reach an IDLE state in the allotted time");
        }

        return client.poolOperations().getPool(poolId);
    }    

    /**
     * Create a job and add some tasks
     * 
     * @param client     The Batch client
     * @param container  A blob container to upload resource files
     * @param poolId     The ID of the pool to submit a job
     * @param jobId      A unique ID for the new job
     * @param taskCount  How many tasks to add
     */
    private static void submitJob(BatchClient client, CloudBlobContainer container, String poolId,
            String jobId, int taskCount)
            throws BatchErrorException, IOException, StorageException, InvalidKeyException, InterruptedException, URISyntaxException {
        System.out.println("Submitting job " + jobId + " with " + taskCount + " tasks");

        // Create job
        PoolInformation poolInfo = new PoolInformation();
        poolInfo.withPoolId(poolId);
        client.jobOperations().createJob(jobId, poolInfo);

        // Upload a resource file and make it available in a "resources" subdirectory on nodes
        String fileName = "test.txt";
        String localPath = "./" + fileName;
        String remotePath = "resources/" + fileName;
        storageSvc.downloadFileFromCloud(container);
        String signedUrl = storageSvc.uploadFileToCloud(container, new File(localPath));
        List<ResourceFile> files = new ArrayList<>();
        files.add(new ResourceFile()
                .withHttpUrl(signedUrl)
                .withFilePath(remotePath));

        // Create tasks
        List<TaskAddParameter> tasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            tasks.add(new TaskAddParameter()
                    .withId("mytask" + i)
                    .withCommandLine("cat " + remotePath)
                    .withResourceFiles(files));
        }

        // Add the tasks to the job
        client.taskOperations().createTasks(jobId, tasks);
    }

    /**
     * Wait for all tasks in a given job to be completed, or throw an exception on timeout
     * 
     * @param client   The Batch client
     * @param jobId    The ID of the job to poll for completion.
     * @param timeout  How long to wait for the job to complete before giving up
     */
    private static void waitForTasksToComplete(BatchClient client, String jobId, Duration timeout)
            throws BatchErrorException, IOException, InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0L;

        System.out.print("Waiting for tasks to complete (Timeout: " + timeout.getSeconds() / 60 + "m)");

        while (elapsedTime < timeout.toMillis()) {
            List<CloudTask> taskCollection = client.taskOperations().listTasks(jobId,
                    new DetailLevel.Builder().withSelectClause("id, state").build());

            boolean allComplete = true;
            for (CloudTask task : taskCollection) {
                if (task.state() != TaskState.COMPLETED) {
                    allComplete = false;
                    break;
                }
            }

            if (allComplete) {
                System.out.println("\nAll tasks completed");
                // All tasks completed
                return;
            }

            System.out.print(".");

            TimeUnit.SECONDS.sleep(10);
            elapsedTime = (new Date()).getTime() - startTime;
        }

        System.out.println();

        throw new TimeoutException("Task did not complete within the specified timeout");
    }

    private static void printBatchException(BatchErrorException err) {
        System.out.printf("BatchError %s%n", err.toString());
        if (err.body() != null) {
            System.out.printf("BatchError code = %s, message = %s%n", err.body().code(),
                    err.body().message().value());
            if (err.body().values() != null) {
                for (BatchErrorDetail detail : err.body().values()) {
                    System.out.printf("Detail %s=%s%n", detail.key(), detail.value());
                }
            }
        }
    }

}