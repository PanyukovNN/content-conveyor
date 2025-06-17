package ru.panyukovnn.contentconveyor.serivce.eventprocessor.impl;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.panyukovnn.contentconveyor.exception.InvalidProcessingEventException;
import ru.panyukovnn.contentconveyor.model.ConveyorTag;
import ru.panyukovnn.contentconveyor.model.PublishingChannel;
import ru.panyukovnn.contentconveyor.model.content.Content;
import ru.panyukovnn.contentconveyor.model.event.ProcessingEvent;
import ru.panyukovnn.contentconveyor.model.event.ProcessingEventType;
import ru.panyukovnn.contentconveyor.property.HardcodedPublishingProperties;
import ru.panyukovnn.contentconveyor.serivce.domain.ContentDomainService;
import ru.panyukovnn.contentconveyor.serivce.domain.ProcessingEventDomainService;
import ru.panyukovnn.contentconveyor.serivce.domain.PublishingChannelDomainService;
import ru.panyukovnn.contentconveyor.serivce.eventprocessor.EventProcessor;
import ru.panyukovnn.contentconveyor.serivce.telegram.TgSender;
import ru.panyukovnn.contentconveyor.util.JsonUtil;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishingEventProcessorImpl implements EventProcessor {

    private final JsonUtil jsonUtil;
    private final TgSender tgSender;
    private final ContentDomainService contentDomainService;
    private final ProcessingEventDomainService processingEventDomainService;
    private final HardcodedPublishingProperties hardcodedPublishingProperties;
    private final PublishingChannelDomainService publishingChannelDomainService;

    @Override
    public void process(ProcessingEvent processingEvent) {
        PublishingChannel publishingChannel = defineChatIdAndTopicId(processingEvent);

        Content content = contentDomainService.findById(processingEvent.getContentId())
            .orElseThrow(() -> new InvalidProcessingEventException("42d6", "Не удалось найти контент"));

        try {
            String contentTitle = content.getTitle();
            String formattedMessage = formatMessage(content, content.getContent());

            tgSender.sendMessage(publishingChannel.getChatId(), publishingChannel.getTopicId(), formattedMessage);

            log.info("Успешно выполнена отправка материала. Название материала: {}. contentId: {}. processingEvent: {}",
                contentTitle, content.getId(), jsonUtil.toJson(processingEvent));

            processingEvent.setType(ProcessingEventType.PUBLISHED);
        } catch (Exception e) {
            log.error("Ошибка при отправке материала в телеграм: {}", e.getMessage(), e);

            processingEvent.setType(ProcessingEventType.PUBLICATION_ERROR);
        } finally {
            processingEventDomainService.save(processingEvent);
        }
    }

    @Override
    public ProcessingEventType getProcessingEventType() {
        return ProcessingEventType.PUBLISHING;
    }

    private PublishingChannel defineChatIdAndTopicId(ProcessingEvent processingEvent) {
        if (processingEvent.getPublishingChannelId() != null) {
            return publishingChannelDomainService.findById(processingEvent.getPublishingChannelId())
                .orElseThrow(() -> new InvalidProcessingEventException("4df0", "Не удалось найти данные о канале публикации"));
        } else if (processingEvent.getConveyorTag() != null) {
            ConveyorTag tag = processingEvent.getConveyorTag();

            Long topicId = switch (tag) {
                case JAVA_HABR -> hardcodedPublishingProperties.getJavaHabrTopicId();
                case TG_MESSAGE_BATCH -> hardcodedPublishingProperties.getTgMessageBatchTopicId();
            };

            return PublishingChannel.builder()
                .chatId(hardcodedPublishingProperties.getChatId())
                .topicId(topicId)
                .build();
        }

        throw new InvalidProcessingEventException("9f9f", "У события отсутствует tag и publishingChannelId, из-за чего невозможно определить канал публикации для материала: " + jsonUtil.toJson(processingEvent));
    }

    private static String formatMessage(@Nullable Content content, String retelling) {
        if (content == null || content.getLink() == null) {
            return retelling;
        }

        String title = Optional.of(content)
            .map(Content::getTitle)
            .filter(StringUtils::hasText)
            .orElse("Ссылка");

        String firstLine = title + "\n" + content.getLink() + "\n\n";

        return firstLine + retelling;
    }

}
