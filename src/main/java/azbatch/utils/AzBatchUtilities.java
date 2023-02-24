package azbatch.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.DetailLevel;
import com.microsoft.azure.batch.protocol.models.AllocationState;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.CloudPool;
import com.microsoft.azure.batch.protocol.models.CloudTask;
import com.microsoft.azure.batch.protocol.models.ComputeNode;
import com.microsoft.azure.batch.protocol.models.ImageInformation;
import com.microsoft.azure.batch.protocol.models.ImageReference;
import com.microsoft.azure.batch.protocol.models.OSType;
import com.microsoft.azure.batch.protocol.models.PoolInformation;
import com.microsoft.azure.batch.protocol.models.PoolState;
import com.microsoft.azure.batch.protocol.models.TaskAddParameter;
import com.microsoft.azure.batch.protocol.models.TaskState;
import com.microsoft.azure.batch.protocol.models.VerificationType;
import com.microsoft.azure.batch.protocol.models.VirtualMachineConfiguration;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import azbatch.constants.AzBatchConfig;

public class AzBatchUtilities {

    private static final Logger logger = LogManager.getLogger(AzBatchUtilities.class);    

    /**
     * Create a pool if one doesn't already exist with the given ID
     *
     * @param client The Batch client
     * @param poolId The ID of the pool to create or look up
     *
     * @return A newly created or existing pool
     */
    public static CloudPool createPoolIfNotExists(BatchClient client)
            throws BatchErrorException, IllegalArgumentException, IOException, InterruptedException, TimeoutException {

        // Create a pool with 1 A1 VM
        String osPublisher = AzBatchConfig.OS_PUBLISHER;
        String osOffer = AzBatchConfig.OS_OFFER;       
        String poolVMSize = AzBatchConfig.POOL_VM_SIZE;
        String  poolId = AzBatchConfig.POOL_ID;
        int poolVMCount = AzBatchConfig.POOL_VM_COUNT;
        int NODE_COUNT = AzBatchConfig.NODE_COUNT;        
        Duration poolSteadyTimeout = Duration.ofMinutes(5);
        Duration vmReadyTimeout = Duration.ofMinutes(20);
        
        // If the pool exists and is active (not being deleted), resize it
        if (client.poolOperations().existsPool(poolId)
                && client.poolOperations().getPool(poolId).state().equals(PoolState.ACTIVE)) {
            logger.info("Pool " + poolId + " already exists: Resizing to " + poolVMCount + " dedicated node(s)");
            client.poolOperations().resizePool(poolId, NODE_COUNT, 0);
        } else {
            logger.info("Creating pool " + poolId + " with " + poolVMCount + " dedicated node(s)");

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

        if (!hasIdleVM) {
            throw new TimeoutException("The node did not reach an IDLE state in the allotted time");
        }
        return client.poolOperations().getPool(poolId);
    }

    /**
     * Create a job and add some tasks
     * 
     * @param client    The Batch client
     * @param container A blob container to upload resource files
     * @param poolId    The ID of the pool to submit a job
     * @param jobId     A unique ID for the new job
     * @param taskCount How many tasks to add
     */
    public static void submitJob(BatchClient client, CloudBlobContainer container, String poolId, String jobId)
            throws BatchErrorException, IOException, StorageException, InvalidKeyException, InterruptedException,
            URISyntaxException {
        int taskCount = AzBatchConfig.TASK_COUNT;
        logger.info("Submitting job " + jobId + " with " + taskCount + " tasks");

        // Create job
        PoolInformation poolInfo = new PoolInformation();
        poolInfo.withPoolId(poolId);
        client.jobOperations().createJob(jobId, poolInfo);

        // // Upload a resource file and make it available in a "resources" subdirectory on
        // // nodes
        // String fileName = "test.txt";
        // String localPath = "./" + fileName;
        // String remotePath = "resources/" + fileName;
        // String signedUrl = uploadFileToCloud(container, new File(localPath));
        // List<ResourceFile> files = new ArrayList<>();
        // files.add(new ResourceFile()
        //         .withHttpUrl(signedUrl)
        //         .withFilePath(remotePath));

        // Create tasks
        List<TaskAddParameter> tasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            tasks.add(new TaskAddParameter()
                        .withId("mytask" + i)
                        .withCommandLine("cat " + "remotePath")
                        //.withResourceFiles(files)                    
                    );
        }
        // Add the tasks to the job
        client.taskOperations().createTasks(jobId, tasks);
    }

    /**
     * Wait for all tasks in a given job to be completed, or throw an exception on
     * timeout
     * 
     * @param client  The Batch client
     * @param jobId   The ID of the job to poll for completion.
     * @param timeout How long to wait for the job to complete before giving up
     */
    public static void waitForTasksToComplete(BatchClient client, String jobId, Duration timeout)
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
                logger.info("\nAll tasks completed");
                // All tasks completed
                return;
            }
            logger.info(".");
            TimeUnit.SECONDS.sleep(10);
            elapsedTime = (new Date()).getTime() - startTime;
        }
        throw new TimeoutException("Task did not complete within the specified timeout");
    }

    /**
     * Upload a file to a blob container and return an SAS key
     *
     * @param container  The container to upload to
     * @param source     The local file to upload
     *
     * @return An SAS key for the uploaded file
     */
    // private static String uploadFileToCloud(CloudBlobContainer container, File source)
    //         throws URISyntaxException, IOException, InvalidKeyException, StorageException {
    //     CloudBlockBlob blob = container.getBlockBlobReference(source.getName());
    //     blob.upload(new FileInputStream(source), source.length());
    //     // Set SAS expiry time to 1 day from now
    //     SharedAccessBlobPolicy policy = new SharedAccessBlobPolicy();
    //     EnumSet<SharedAccessBlobPermissions> perEnumSet = EnumSet.of(SharedAccessBlobPermissions.READ);
    //     policy.setPermissions(perEnumSet);
    //     Calendar cal = Calendar.getInstance();
    //     cal.setTime(new Date());
    //     cal.add(Calendar.DATE, 1);
    //     policy.setSharedAccessExpiryTime(cal.getTime());
    //     // Create SAS key
    //     String sas = blob.generateSharedAccessSignature(policy, null);
    //     return blob.getUri() + "?" + sas;
    // }

}