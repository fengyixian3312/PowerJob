package tech.powerjob.samples.processors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;
import tech.powerjob.worker.log.OmsLogger;

@Component
@Slf4j
public class TestDemo implements BasicProcessor {
    @Override
    public ProcessResult process(TaskContext context) throws Exception {
        OmsLogger omsLogger = context.getOmsLogger();
        omsLogger.info("启动进程，上下文是{}.", context);
        System.out.println("job参数是 " + context.getJobParams());

        return new ProcessResult(true, "process successfully~");
    }
}
