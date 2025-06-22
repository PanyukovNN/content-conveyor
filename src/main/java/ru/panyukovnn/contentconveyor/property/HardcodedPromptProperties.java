package ru.panyukovnn.contentconveyor.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "retelling.hardcoded-prompts")
public class HardcodedPromptProperties {

    private String youtubeRetelling;
    private String javaArticleRateMaterial;
    private String javaArticleRetelling;
    private String tgMessageBatchRetelling;
}
