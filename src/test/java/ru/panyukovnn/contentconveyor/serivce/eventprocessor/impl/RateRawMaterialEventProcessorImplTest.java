package ru.panyukovnn.contentconveyor.serivce.eventprocessor.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.panyukovnn.contentconveyor.client.OpenAiClient;
import ru.panyukovnn.contentconveyor.exception.RawMaterialRateException;
import ru.panyukovnn.contentconveyor.model.ConveyorTag;
import ru.panyukovnn.contentconveyor.model.content.Content;
import ru.panyukovnn.contentconveyor.model.content.ContentRate;
import ru.panyukovnn.contentconveyor.model.event.ProcessingEvent;
import ru.panyukovnn.contentconveyor.model.event.ProcessingEventType;
import ru.panyukovnn.contentconveyor.property.HardcodedPromptProperties;
import ru.panyukovnn.contentconveyor.property.HardcodedPublishingProperties;
import ru.panyukovnn.contentconveyor.property.RateProperties;
import ru.panyukovnn.contentconveyor.repository.ContentRateRepository;
import ru.panyukovnn.contentconveyor.repository.ContentRepository;
import ru.panyukovnn.contentconveyor.serivce.domain.ProcessingEventDomainService;
import ru.panyukovnn.contentconveyor.serivce.telegram.TgSender;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateRawMaterialEventProcessorImplTest {

    @Mock
    private TgSender tgSender;
    @Mock
    private OpenAiClient openAiClient;
    @Mock
    private RateProperties rateProperties;
    @Mock
    private ContentRepository contentRepository;
    @Mock
    private ContentRateRepository contentRateRepository;
    @Mock
    private HardcodedPromptProperties hardcodedPromptProperties;
    @Mock
    private ProcessingEventDomainService processingEventDomainService;
    @Mock
    private HardcodedPublishingProperties hardcodedPublishingProperties;

    @InjectMocks
    private RateRawMaterialEventProcessorImpl rateRawMaterialEventProcessor;

    @Test
    void when_process_withValidContent_then_success() {
        // Arrange
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setTitle("Test Title");
        content.setLink("https://test.com");
        content.setContent("Test Content");

        ProcessingEvent processingEvent = new ProcessingEvent();
        processingEvent.setContentId(contentId);
        processingEvent.setConveyorTag(ConveyorTag.JAVA_HABR);

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(hardcodedPromptProperties.getJavaHabrRateMaterial()).thenReturn("Rate prompt");
        when(openAiClient.promptingCall(anyString(), anyString(), anyString()))
            .thenReturn("75 - Good content");
        when(rateProperties.getThreshold()).thenReturn(50);
        when(contentRateRepository.save(any(ContentRate.class))).thenAnswer(i -> i.getArgument(0));
        when(hardcodedPublishingProperties.getChatId()).thenReturn(123456789L);
        when(hardcodedPublishingProperties.getRateTgTopicId()).thenReturn(987654321L);

        // Act
        rateRawMaterialEventProcessor.process(processingEvent);

        // Assert
        verify(contentRepository).findById(contentId);
        verify(openAiClient).promptingCall("rate_material", "Rate prompt", "Test Content");
        verify(contentRateRepository).save(argThat(rate ->
            rate.getContentId().equals(contentId) &&
            rate.getRate() == 75 &&
            rate.getPrompt().equals("Rate prompt") &&
            rate.getGrounding().equals("75 - Good content")
        ));
        verify(processingEventDomainService).save(argThat(event ->
            event.getType() == ProcessingEventType.RETELLING
        ));
        verify(tgSender).sendMessage(eq(123456789L), eq(987654321L), anyString());
    }

    @Test
    void when_process_withUnderratedContent_then_markAsUnderrated() {
        // Arrange
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setTitle("Test Title");
        content.setLink("https://test.com");
        content.setContent("Test Content");

        ProcessingEvent processingEvent = new ProcessingEvent();
        processingEvent.setContentId(contentId);
        processingEvent.setConveyorTag(ConveyorTag.JAVA_HABR);

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(hardcodedPromptProperties.getJavaHabrRateMaterial()).thenReturn("Rate prompt");
        when(openAiClient.promptingCall(anyString(), anyString(), anyString()))
            .thenReturn("35 - Poor content");
        when(rateProperties.getThreshold()).thenReturn(50);
        when(contentRateRepository.save(any(ContentRate.class))).thenAnswer(i -> i.getArgument(0));
        when(hardcodedPublishingProperties.getChatId()).thenReturn(123456789L);
        when(hardcodedPublishingProperties.getRateTgTopicId()).thenReturn(987654321L);

        // Act
        rateRawMaterialEventProcessor.process(processingEvent);

        // Assert
        verify(processingEventDomainService).save(argThat(event ->
            event.getType() == ProcessingEventType.UNDERRATED
        ));
    }

    @Test
    void when_process_withInvalidRate_then_throwException() {
        // Arrange
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setContent("Test Content");

        ProcessingEvent processingEvent = new ProcessingEvent();
        processingEvent.setContentId(contentId);
        processingEvent.setConveyorTag(ConveyorTag.JAVA_HABR);

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(hardcodedPromptProperties.getJavaHabrRateMaterial()).thenReturn("Rate prompt");
        when(openAiClient.promptingCall(anyString(), anyString(), anyString()))
            .thenReturn("Invalid rate format");

        // Act & Assert
        assertThrows(RawMaterialRateException.class, () -> 
            rateRawMaterialEventProcessor.process(processingEvent)
        );
    }

    @Test
    void when_process_withNonExistentContent_then_deleteEvent() {
        // Arrange
        UUID contentId = UUID.randomUUID();
        ProcessingEvent processingEvent = new ProcessingEvent();
        processingEvent.setContentId(contentId);
        processingEvent.setConveyorTag(ConveyorTag.JAVA_HABR);

        when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

        // Act
        rateRawMaterialEventProcessor.process(processingEvent);

        // Assert
        verify(processingEventDomainService).delete(processingEvent);
        verifyNoMoreInteractions(openAiClient, contentRateRepository, tgSender);
    }

    @Test
    void when_getProcessingEventType_then_returnRateRawMaterial() {
        // Act
        ProcessingEventType type = rateRawMaterialEventProcessor.getProcessingEventType();

        // Assert
        assertEquals(ProcessingEventType.RATE_RAW_MATERIAL, type);
    }
} 