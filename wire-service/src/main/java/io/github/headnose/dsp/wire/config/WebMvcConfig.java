package io.github.headnose.dsp.wire.config;

import io.github.headnose.dsp.wire.logging.MdcKeys;
import io.github.headnose.dsp.wire.logging.PathIdMdcInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** O3: submissionId from /api/v1/wires/{submissionId}/send into the MDC. */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PathIdMdcInterceptor(MdcKeys.SUBMISSION_ID));
    }
}