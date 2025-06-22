package ru.panyukovnn.contentconveyor.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.panyukovnn.contentconveyor.dto.chathistory.ChatHistoryResponse;
import ru.panyukovnn.contentconveyor.model.ConveyorType;
import ru.panyukovnn.contentconveyor.model.PublishingChannel;
import ru.panyukovnn.contentconveyor.model.Source;
import ru.panyukovnn.contentconveyor.model.content.Content;
import ru.panyukovnn.contentconveyor.model.content.ContentType;
import ru.panyukovnn.contentconveyor.model.event.ProcessingEvent;
import ru.panyukovnn.contentconveyor.model.parsingjobinfo.ParsingJobInfo;
import ru.panyukovnn.contentconveyor.model.parsingjobinfo.SourceDetails;
import ru.panyukovnn.contentconveyor.serivce.TgChatsCollectorClientService;
import ru.panyukovnn.contentconveyor.serivce.domain.ContentDomainService;
import ru.panyukovnn.contentconveyor.serivce.domain.ParsingJobInfoDomainService;
import ru.panyukovnn.contentconveyor.serivce.domain.ProcessingEventDomainService;
import ru.panyukovnn.contentconveyor.serivce.telegram.TgSender;
import ru.panyukovnn.contentconveyor.util.JsonUtil;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParsingJob {

    private final JsonUtil jsonUtil;
    private final TgSender tgSender;
    private final ContentDomainService contentDomainService;
    private final ParsingJobInfoDomainService parsingJobInfoDomainService;
    private final ProcessingEventDomainService processingEventDomainService;
    private final TgChatsCollectorClientService tgChatsCollectorClientService;

    @Async("parsingJobScheduler")
    @Scheduled(cron = "0 0 8 * * *")
    public void parsing() {
        List<ParsingJobInfo> dailyParsingJobs = parsingJobInfoDomainService.findDailyParsingJobs();

        dailyParsingJobs.forEach(parsingJob -> {
            try {
                if (Source.TG.equals(parsingJob.getSource())) {
                    SourceDetails sourceDetails = parsingJob.getSourceDetails();
                    PublishingChannel publishingChannel = parsingJob.getPublishingChannel();

                    ChatHistoryResponse lastDayChatHistory = tgChatsCollectorClientService.getLastDayChatHistory(
                        sourceDetails.getTgChatNamePart(),
                        sourceDetails.getTgTopicNamePart()
                    );

                    if (lastDayChatHistory.getChatId() == 0L) {
                        tgSender.sendMessage(publishingChannel, "Ошибка получения сообщений из чата: " + sourceDetails);

                        return;
                    }

                    startConveyor(parsingJob, lastDayChatHistory);
                }
            } catch (Exception e) {
                log.error("Ошибка ежедневного сбора информации из источника: {}", e.getMessage(), e);
            }
        });
    }

    private void startConveyor(ParsingJobInfo parsingJob, ChatHistoryResponse lastDayChatHistory) {
        UUID parentBatchId = UUID.randomUUID();
        UUID childBatchId = UUID.randomUUID();

        lastDayChatHistory.getMessageBatches().forEach(messagesBatch -> {
            Content content = Content.builder()
                .link(lastDayChatHistory.getChatId().toString())
                .type(ContentType.TG_MESSAGE_BATCH)
                .source(Source.TG)
                .title(lastDayChatHistory.getChatTitle() + " / " + lastDayChatHistory.getTopicName() + " - за последние 24 часа")
                .publicationDate(lastDayChatHistory.getFirstMessageDateTime())
                .content(jsonUtil.toJson(messagesBatch))
                .parentBatchId(parentBatchId)
                .childBatchId(childBatchId)
                .build();

            contentDomainService.save(content);
        });

        ConveyorType conveyorType = parsingJob.getConveyorType();

        ProcessingEvent reduceProcessingEvent = ProcessingEvent.builder()
            .type(conveyorType.getStartEventType())
            .conveyorType(conveyorType)
            .contentId(null)
            .contentBatchId(parentBatchId)
            .promptId(parsingJob.getPrompt().getId())
            .publishingChannelId(parsingJob.getPublishingChannel().getId())
            .build();
        processingEventDomainService.save(reduceProcessingEvent);
    }
}
