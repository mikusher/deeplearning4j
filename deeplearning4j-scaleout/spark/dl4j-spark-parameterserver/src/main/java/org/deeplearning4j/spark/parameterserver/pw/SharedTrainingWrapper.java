package org.deeplearning4j.spark.parameterserver.pw;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.exception.DL4JInvalidConfigException;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.SleepyTrainingListener;
import org.deeplearning4j.optimize.solvers.accumulation.CudaGradientsAccumulator;
import org.deeplearning4j.optimize.solvers.accumulation.MessageHandler;
import org.deeplearning4j.parallelism.ParallelWrapper;
import org.deeplearning4j.spark.parameterserver.conf.SharedTrainingConfiguration;
import org.deeplearning4j.spark.parameterserver.iterators.VirtualDataSetIterator;
import org.deeplearning4j.spark.parameterserver.iterators.VirtualIterator;
import org.deeplearning4j.spark.parameterserver.iterators.VirtualMultiDataSetIterator;
import org.deeplearning4j.spark.parameterserver.networking.SilentTrainingDriver;
import org.deeplearning4j.spark.parameterserver.networking.WiredEncodingHandler;
import org.deeplearning4j.spark.parameterserver.networking.messages.SilentIntroductoryMessage;
import org.deeplearning4j.spark.parameterserver.training.SharedTrainingResult;
import org.deeplearning4j.spark.parameterserver.training.SharedTrainingWorker;
import org.deeplearning4j.spark.parameterserver.util.BlockingObserver;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.distributed.VoidParameterServer;
import org.nd4j.parameterserver.distributed.conf.VoidConfiguration;
import org.nd4j.parameterserver.distributed.enums.NodeRole;
import org.nd4j.parameterserver.distributed.enums.TransportType;
import org.nd4j.parameterserver.distributed.transport.MulticastTransport;
import org.nd4j.parameterserver.distributed.transport.RoutedTransport;
import org.nd4j.parameterserver.distributed.transport.Transport;

import java.util.Iterator;
import java.util.List;
import java.util.Observer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class maintains ParallelWrapper instance in Spark environment, and provides primitives for inter-executor
 * communication during training over partitions.
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class SharedTrainingWrapper {
    public static SharedTrainingWrapper INSTANCE = new SharedTrainingWrapper();
    protected ParallelWrapper wrapper;
    protected VirtualDataSetIterator iteratorDS;
    protected VirtualMultiDataSetIterator iteratorMDS;

    protected List<Iterator<DataSet>> iteratorsDS;
    protected List<Iterator<MultiDataSet>> iteratorsMDS;


    protected AtomicBoolean isFirst = new AtomicBoolean(false);

    protected ThreadLocal<BlockingObserver> observer = new ThreadLocal<>();
    protected CudaGradientsAccumulator accumulator;

    protected SharedTrainingWrapper() {
        init();
    }

    protected void init() {
        // instantiate some stuff here
        iteratorsDS = new CopyOnWriteArrayList<>();
        iteratorsMDS = new CopyOnWriteArrayList<>();

        // now we're creating DataSetIterators, to feed ParallelWrapper
        iteratorDS = new VirtualDataSetIterator(iteratorsDS);
    }

    public static SharedTrainingWrapper getInstance() {
        return INSTANCE;
    }

    /**
     * This method registers given Iterable<DataSet> in VirtualDataSetIterator
     *
     * @param iterator
     */
    public void attachDS(Iterator<DataSet> iterator) {
        log.info("Attaching thread...");

        // we're creating our Observable wrapper
        VirtualIterator<DataSet> wrapped = new VirtualIterator<>(iterator);

        // and creating Observer which will be used to monitor progress within iterator
        BlockingObserver obs = new BlockingObserver();
        wrapped.addObserver(obs);

        // putting that "somewhere"
        iteratorsDS.add(wrapped);

        // storing observer into ThreadLocal, since we're going to use that later
        observer.set(obs);
    }

    /**
     * This method registers given Iterable<MultiDataSet> in VirtualMultiDataSetIterator
     *
     * @param iterator
     */
    public void attachMDS(Iterator<MultiDataSet> iterator) {
        log.info("Attaching thread...");

        // we're creating our Observable wrapper
        VirtualIterator<MultiDataSet> wrapped = new VirtualIterator<>(iterator);

        // and creating Observer which will be used to monitor progress within iterator
        BlockingObserver obs = new BlockingObserver();
        wrapped.addObserver(obs);

        // putting that "somewhere"
        iteratorsMDS.add(wrapped);

        // storing observer into ThreadLocal, since we're going to use that later
        observer.set(obs);
    }

    public SharedTrainingResult run(SharedTrainingWorker worker) {
        /*
            first call instantiates pw, messenger etc, and gets in charge here.
         */
        if (isFirst.compareAndSet(false, true)) {
            // getting model from worker, and instantiating PW
            try {
                Thread.sleep(5000);
            } catch (Exception e) {
                //
            }

            SharedTrainingConfiguration trainingConfiguration = worker.getBroadcastConfiguration().getValue();
            VoidConfiguration voidConfiguration = worker.getBroadcastConfiguration().getValue().getVoidConfiguration();

            Model model = null;

            // now we're attaching VoidParameterServer to GradientsAccumulator, but doing that only once
            if (wrapper == null) {
                log.info("Starting ParallelWrapper at thread {}", Thread.currentThread().getId());

                model = worker.getInitialModel();
                if (model == null)
                    model = worker.getInitialModelGraph();

                if (model == null)
                    throw new DL4JInvalidConfigException("No model was defined for training");

                MessageHandler handler = new WiredEncodingHandler(trainingConfiguration.getThreshold());

                // this accumulator will provide sharing gradients over network, via WiredEncodedHandler. But we create it only once
                if (accumulator == null) {
                    accumulator = new CudaGradientsAccumulator.Builder(2)
                            .messageHandler(handler)
                            .encodingThreshold(trainingConfiguration.getThreshold())
                            // TODO: make this configurable
                            .memoryParameters(200 * 1024 * 1024L, 10)
                            .build();

                    // FIXME: implement support for Custom transport implementation
                    Transport transport = voidConfiguration.getTransportType() == TransportType.ROUTED ? new RoutedTransport() : voidConfiguration.getTransportType() == TransportType.BROADCAST ? new MulticastTransport() : null;

                    if (transport == null)
                        throw new DL4JInvalidConfigException("No Transport implementation was defined for this training session!");

                    // let's check for spark local edge case
                    if (!VoidParameterServer.getInstance().isInit()) {
                        // all nodes that are NOT master - enforced to be Clients
                        voidConfiguration.setForcedRole(null);

                        // TODO: tbd: let's allow one of executor nodes to be silent worker maybe? or this going to be too expensive?
                    }

                    VoidParameterServer.getInstance().init(voidConfiguration, transport, new SilentTrainingDriver(accumulator));

                    // we should introduce ourselves to controller
                    // FIXME: if localIP is null - use original ip discovery available in VoidParameterServer
                    String localIP = System.getenv("SPARK_PUBLIC_DNS");

                    // FIXME: do we need port here, in case of Multicast/Broadcast Transport?
                    SilentIntroductoryMessage sim = new SilentIntroductoryMessage(localIP, voidConfiguration.getUnicastPort());

                    // we're sending this message to all shards, though it's just one Shard by design here - Spark Master
                    VoidParameterServer.getInstance().sendMessageToAllShards(sim);

                    // after initialization finished, we're ok to actually start training
                }

                /*
                    Plan is simple here: if there's defined field in SharedTrainingConfiguration - use that.
                    If no - try to guess something
                 */
                int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();

                int numWorkers = trainingConfiguration.getNumberOfWorkersPerNode() > 0 ? trainingConfiguration.getNumberOfWorkersPerNode() : numDevices > 1 ? numDevices : 2;

                if (numDevices > 1 && numWorkers > numDevices)
                    log.warn("WARNING! Using more workers then number of available computational devices!");

                // if we're going to extend iteratation for debugging purposes - let's do that here
                if (trainingConfiguration.getDebugLongerIterations() > 0)
                    model.addListener(SleepyTrainingListener.builder().timerIteration(trainingConfiguration.getDebugLongerIterations()).build());

                // we're launching PW only if number of workers is more then 1
                if (numWorkers > 1) {
                    wrapper = new ParallelWrapper.Builder<>(model)
                            .workers(numWorkers)
                            .workspaceMode(trainingConfiguration.getWorkspaceMode())
                            .trainingMode(ParallelWrapper.TrainingMode.CUSTOM)
                            .gradientsAccumulator(accumulator)
                            .prefetchBuffer(trainingConfiguration.getPrefetchSize())
                            .build();
                } else {
                    log.info("Using standalone model instead...");
                    // ok. attaching accumulator to
                    if (model instanceof ComputationGraph) {
                        ((ComputationGraph) model).setGradientsAccumulator(accumulator);
                    } else if (model instanceof MultiLayerNetwork) {
                        ((MultiLayerNetwork) model).setGradientsAccumulator(accumulator);
                    }
                }
            }

            // TODO: optionally we might be waiting until we have >1 splits delivered

            // now we're just calling for fit
            if (wrapper != null) {
                if (iteratorDS != null)
                    wrapper.fit(iteratorDS);
                else if (iteratorMDS != null)
                    wrapper.fit(iteratorMDS);
                else
                    throw new DL4JInvalidConfigException("No iterators were defined for training");
            } else {
                // if wrapper is null, we're fitting standalone model then
                if (iteratorDS != null) {
                    if (model instanceof ComputationGraph) {
                        ((ComputationGraph) model).fit(iteratorDS);
                    } else if (model instanceof MultiLayerNetwork) {
                        ((MultiLayerNetwork) model).fit(iteratorDS);
                    }
                } else if (iteratorMDS != null) {
                    ((ComputationGraph) model).fit(iteratorMDS);
                } else
                    throw new DL4JInvalidConfigException("No iterators were defined for training");
            }


            // conditionally shutdown & reset ParallelWrapper
            if (trainingConfiguration.isEpochReset()) {
                wrapper.shutdown();
                wrapper = null;
            }

            // reset iterators too
            init();

            // and accumulator, to reset its states
            accumulator.reset();


            isFirst.set(false);

            log.info("Master thread done...");

            // TODO: we want to give back updaters here
            return new SharedTrainingResult();
        } else {
            // blocking call right here, all non-master threads will be blocked here
            try {
                observer.get().waitTillDone();
                //observer.get().wait();

                log.info("Feeder thread done...");

                //  nothing to do here, just give away empty result
                return new SharedTrainingResult();
            } catch (InterruptedException e) {
                // FIXME: we don't really need to throw it again, it's here only for debugging purposes
                throw new RuntimeException(e);
            }
        }
    }

    public void passDataSet(DataSet dataSet) {
        // we're going to save this dataset into VirtualDataSetIterator
    }

    public void passDataSet(MultiDataSet dataSet) {
        // we're going to save this dataset into VirtualMultiDataSetIterator
    }


    public void blockUntilFinished() throws InterruptedException {
        if (observer.get() != null)
            observer.get().wait();
        else
            throw new IllegalStateException("This method can't be called before iterators initialization");
    }
}
