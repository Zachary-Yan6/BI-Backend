package com.zachary.BI.BiMq;

import com.rabbitmq.client.Channel;
import com.zachary.BI.GenAi;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;
import com.zachary.BI.service.AnalysisCompletionService;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Date;

@Component
@Slf4j
public class BiMessageConsumer {
    private static final long BASE_RETRY_DELAY_MILLIS = 2_000L;

    @Resource
    private ChartService chartService;
    @Resource
    private AnalysisJobService analysisJobService;
    @Resource
    private BiMessageProducer biMessageProducer;
    @Resource
    private GenAi genAi;
    @Resource
    private AnalysisCompletionService analysisCompletionService;
    @Resource
    private AnalysisResultParser analysisResultParser;

    @RabbitListener(queues = BiMqConstant.BI_QUEUE_NAME, ackMode = "MANUAL")
    public void receiveMessage(String message, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        long jobId;
        try {
            jobId = Long.parseLong(message);
        } catch (NumberFormatException exception) {
            log.error("Discarding malformed analysis job message: {}", message);
            channel.basicAck(deliveryTag, false);
            return;
        }

        AnalysisJob job = analysisJobService.getById(jobId);
        if (job == null || !AnalysisJobStatusEnum.isActive(job.getStatus()) || !analysisJobService.start(jobId)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        Chart chart = chartService.getById(job.getChartId());
        if (chart == null) {
            // Retrying cannot bring a deleted chart back. Throwing here would leave the message unacknowledged
            // and the job stuck in running, so finish the job and acknowledge instead.
            log.warn("Analysis job {} refers to missing chart {}", jobId, job.getChartId());
            analysisJobService.fail(jobId, "The chart record no longer exists.");
            channel.basicAck(deliveryTag, false);
            return;
        }

        String genChart;
        String genResult;

        try {
            // An answer without a valid JSON option or a conclusion is treated like a failed call and retried.
            AnalysisResultParser.AnalysisResult result = analysisResultParser.parse(genAi.doChat(buildUserInput(chart)));
            genChart = result.chartOption();
            genResult = result.conclusion();

        } catch (Exception aiException) {
            // AI call or AI response format failed: use normal delayed retry logic.
            log.error("Analysis job {} failed during AI processing", jobId, aiException);
            handleFailure(jobId, job, aiException.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        }


        try {
            // chart, analysis_job, and analysis_job_event are persisted in one MySQL transaction.
            analysisCompletionService.persistSuccess(
                    jobId,
                    chart.getId(),
                    genChart,
                    genResult
            );

        } catch (Exception persistenceException) {
            log.error(
                    "Analysis job {} produced a result but could not persist it",
                    jobId,
                    persistenceException
            );

            // Treat database persistence failure as a retryable infrastructure failure.
            // handleFailure changes the task to retrying, records the reason,
            // and sends the job to the delayed RabbitMQ retry queue.
            handleFailure(
                    jobId,
                    job,
                    "Result persistence failed: " + persistenceException.getMessage()
            );

            // The retry message has been scheduled, so acknowledge the original message.
            channel.basicAck(deliveryTag, false);
            return;
        }

// ACK only after the MySQL transaction has committed successfully.
        channel.basicAck(deliveryTag, false);
    }


    private void handleFailure(long jobId, AnalysisJob job, String reason) {
        int retryAttempt = analysisJobService.scheduleRetry(jobId, reason);
        if (retryAttempt < 0) {
            // Another actor (normally the recovery task) already owns this job; failing it here would
            // overwrite that decision.
            log.warn("Analysis job {} is no longer running; skipping failure handling", jobId);
            return;
        }
        if (retryAttempt > 0) {
            try {
                biMessageProducer.scheduleRetry(jobId, BASE_RETRY_DELAY_MILLIS * (1L << (retryAttempt - 1)));
                updateChartStatus(job.getChartId(), AnalysisJobStatusEnum.RETRYING.getValue(), reason);
                return;
            } catch (RuntimeException retryPublishError) {
                reason = "Retry scheduling failed: " + retryPublishError.getMessage();
                log.error("Could not schedule retry for analysis job {}", jobId, retryPublishError);
            }
        }
        analysisJobService.fail(jobId, reason);
        updateChartStatus(job.getChartId(), AnalysisJobStatusEnum.FAILED.getValue(), reason);
        try {
            biMessageProducer.sendToDeadLetterQueue(jobId);
        } catch (RuntimeException deadLetterError) {
            log.error("Could not publish analysis job {} to the dead-letter queue", jobId, deadLetterError);
        }
    }

    private void updateChartStatus(long chartId, String status, String message) {
        Chart update = new Chart();
        update.setId(chartId);
        update.setStatus(status);
        update.setExecMessage(message);
        chartService.updateById(update);
    }

    private String buildUserInput(Chart chart) {
        StringBuilder userInput = new StringBuilder();
        userInput.append("You are a data analyst. I will provide an analysis goal and a dataset. Analyse the data:\n");
        userInput.append("My goal: ").append(chart.getGoal()).append("\n");
        if (StringUtils.isNotBlank(chart.getChartType())) {
            userInput.append("Chart type: ").append(chart.getChartType()).append("\n");
        }
        userInput.append("My data: ").append(chart.getChartData()).append("\n");
        userInput.append("Provide an ECharts option that can be rendered in the frontend. It must be strict JSON: "
                + "double-quoted keys and strings, no JavaScript functions, no comments. Return only the JSON object, "
                + "then the analysis conclusion, separated by -----. Respond in English.\n");
        return userInput.toString();
    }
}
