package tech.powerjob.server.core.handler;

import akka.actor.Props;
import akka.routing.RoundRobinPool;
import tech.powerjob.common.enums.InstanceStatus;
import tech.powerjob.common.RemoteConstant;
import tech.powerjob.common.request.*;
import tech.powerjob.server.core.handler.impl.WorkerRequestAkkaHandler;
import tech.powerjob.server.core.handler.impl.WorkerRequestHttpHandler;
import tech.powerjob.server.core.instance.InstanceLogService;
import tech.powerjob.server.core.instance.InstanceManager;
import tech.powerjob.server.core.workflow.WorkflowInstanceManager;
import tech.powerjob.server.remote.transport.starter.AkkaStarter;
import tech.powerjob.server.remote.transport.starter.VertXStarter;
import tech.powerjob.server.remote.worker.WorkerClusterQueryService;
import tech.powerjob.server.common.module.WorkerInfo;
import tech.powerjob.common.response.AskResponse;
import tech.powerjob.common.serialize.JsonUtils;
import tech.powerjob.common.utils.NetUtils;
import tech.powerjob.server.common.constants.SwitchableStatus;
import tech.powerjob.server.common.utils.SpringUtils;
import tech.powerjob.server.persistence.remote.model.ContainerInfoDO;
import tech.powerjob.server.persistence.remote.model.JobInfoDO;
import tech.powerjob.server.persistence.remote.repository.ContainerInfoRepository;
import tech.powerjob.server.persistence.remote.repository.JobInfoRepository;
import tech.powerjob.server.remote.worker.WorkerClusterManagerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * receive and process worker's request
 *
 * @author tjq
 * @since 2021/2/8
 */
@Slf4j
@Component
public class WorkerRequestHandler {

    @Resource
    private Environment environment;
    @Resource
    private InstanceManager instanceManager;
    @Resource
    private WorkflowInstanceManager workflowInstanceManager;
    @Resource
    private InstanceLogService instanceLogService;
    @Resource
    private ContainerInfoRepository containerInfoRepository;

    @Resource
    private WorkerClusterQueryService workerClusterQueryService;

    private static WorkerRequestHandler workerRequestHandler;

    @PostConstruct
    public void initHandler() {
        // init akka
        AkkaStarter.actorSystem.actorOf(Props.create(WorkerRequestAkkaHandler.class)
                .withDispatcher("akka.server-actor-dispatcher")
                .withRouter(new RoundRobinPool(Runtime.getRuntime().availableProcessors() * 4)), RemoteConstant.SERVER_ACTOR_NAME);
        // init vert.x
        VertXStarter.vertx.deployVerticle(new WorkerRequestHttpHandler());
    }

    /**
     * ?????? Worker ???????????????
     * @param heartbeat ?????????
     */
    public void onReceiveWorkerHeartbeat(WorkerHeartbeat heartbeat) {
        WorkerClusterManagerService.updateStatus(heartbeat);
    }

    /**
     * ?????? instance ??????
     * @param req ?????????????????????????????????
     */
    public Optional<AskResponse> onReceiveTaskTrackerReportInstanceStatusReq(TaskTrackerReportInstanceStatusReq req) throws ExecutionException {
        // 2021/02/05 ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        if (req.getWfInstanceId() != null && !CollectionUtils.isEmpty(req.getAppendedWfContext())) {
            // ??????????????????????????????
            workflowInstanceManager.updateWorkflowContext(req.getWfInstanceId(),req.getAppendedWfContext());
        }

        instanceManager.updateStatus(req);

        // ?????????????????????/???????????????????????????
        if (InstanceStatus.FINISHED_STATUS.contains(req.getInstanceStatus())) {
            return Optional.of(AskResponse.succeed(null));
        }
        return Optional.empty();
    }

    /**
     * ??????OMS??????????????????
     * @param req ????????????
     */
    public void onReceiveWorkerLogReportReq(WorkerLogReportReq req) {
        // ?????????????????????????????????...????????????????????? + Map#get ???...
        instanceLogService.submitLogs(req.getWorkerAddress(), req.getInstanceLogContents());
    }

    /**
     * ?????? Worker??????????????????
     * @param req ??????????????????
     */
    public AskResponse onReceiveWorkerNeedDeployContainerRequest(WorkerNeedDeployContainerRequest req) {

        String port = environment.getProperty("local.server.port");

        Optional<ContainerInfoDO> containerInfoOpt = containerInfoRepository.findById(req.getContainerId());
        AskResponse askResponse = new AskResponse();
        if (!containerInfoOpt.isPresent() || containerInfoOpt.get().getStatus() != SwitchableStatus.ENABLE.getV()) {
            askResponse.setSuccess(false);
            askResponse.setMessage("can't find container by id: " + req.getContainerId());
        }else {
            ContainerInfoDO containerInfo = containerInfoOpt.get();
            askResponse.setSuccess(true);

            ServerDeployContainerRequest dpReq = new ServerDeployContainerRequest();
            BeanUtils.copyProperties(containerInfo, dpReq);
            dpReq.setContainerId(containerInfo.getId());
            String downloadURL = String.format("http://%s:%s/container/downloadJar?version=%s", NetUtils.getLocalHost(), port, containerInfo.getVersion());
            dpReq.setDownloadURL(downloadURL);

            askResponse.setData(JsonUtils.toBytes(dpReq));
        }
        return askResponse;
    }

    /**
     * ?????? worker ??????????????????????????????????????????????????????
     * @param req jobId + appId
     */
    public AskResponse onReceiveWorkerQueryExecutorClusterReq(WorkerQueryExecutorClusterReq req) {

        AskResponse askResponse;

        Long jobId = req.getJobId();
        Long appId = req.getAppId();

        JobInfoRepository jobInfoRepository = SpringUtils.getBean(JobInfoRepository.class);
        Optional<JobInfoDO> jobInfoOpt = jobInfoRepository.findById(jobId);
        if (jobInfoOpt.isPresent()) {
            JobInfoDO jobInfo = jobInfoOpt.get();
            if (!jobInfo.getAppId().equals(appId)) {
                askResponse = AskResponse.failed("Permission Denied!");
            }else {
                List<String> sortedAvailableWorker = workerClusterQueryService.getSuitableWorkers(jobInfo)
                        .stream().map(WorkerInfo::getAddress).collect(Collectors.toList());
                askResponse = AskResponse.succeed(sortedAvailableWorker);
            }
        }else {
            askResponse = AskResponse.failed("can't find jobInfo by jobId: " + jobId);
        }
        return askResponse;
    }

    public static WorkerRequestHandler getWorkerRequestHandler() {
        if (workerRequestHandler == null) {
            workerRequestHandler = SpringUtils.getBean(WorkerRequestHandler.class);
        }
        return workerRequestHandler;
    }
}
