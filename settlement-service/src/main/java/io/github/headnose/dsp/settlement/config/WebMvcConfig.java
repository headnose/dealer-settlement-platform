package io.github.headnose.dsp.settlement.config;

import io.github.headnose.dsp.settlement.logging.MdcKeys;
import io.github.headnose.dsp.settlement.logging.PathIdMdcInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** O3: loanId from /api/v1/loans/{loanId}/... into the MDC (GET and PATCH settlements). */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PathIdMdcInterceptor(MdcKeys.LOAN_ID));
    }
}