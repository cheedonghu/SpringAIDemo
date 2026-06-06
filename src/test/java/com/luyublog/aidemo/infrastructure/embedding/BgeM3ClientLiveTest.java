package com.luyublog.aidemo.infrastructure.embedding;

import com.luyublog.aidemo.domain.embedding.EmbedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BgeM3Client 的烟囱测试，依赖 :8002 上的 FastAPI 服务真实在跑。
 *
 * <p>默认禁用，避免没起服务时 CI/普通 mvn test 失败；要跑就显式开启：
 * <pre>{@code
 *   ./mvnw -o -Dmaven.repo.local=.m2repo test \
 *     -Dtest=BgeM3ClientLiveTest -Dbgem3.live=true
 * }</pre>
 * <p>
 * 还可用 {@code -Dbgem3.base-url=http://other-host:8002} 覆盖默认地址。
 */
@EnabledIfSystemProperty(named = "bgem3.live", matches = "true")
class BgeM3ClientLiveTest {

    private static final int EXPECTED_DENSE_DIM = 1024;

    private final BgeM3Client client = new BgeM3Client(
            System.getProperty("bgem3.base-url", "http://localhost:8002"),
            12, 20, 300, 30);

    @Test
    void embedSingleReturnsDenseAndSparse() {
        EmbedResult result = this.client.embed("对账渠道有哪些");

        assertNotNull(result, "result");
        assertNotNull(result.dense(), "dense");
        assertEquals(EXPECTED_DENSE_DIM, result.dense().length, "dense dim");
        assertNotNull(result.sparse(), "sparse");
        assertFalse(result.sparse().isEmpty(), "sparse should not be empty");

        System.out.printf("dense.length=%d, sparse.size=%d%n",
                result.dense().length, result.sparse().size());
    }

    @Test
    void embedBatchReturnsResultsInOrder() {
        List<String> inputs = List.of("对账渠道有哪些", "如何注册商户");
        List<EmbedResult> results = this.client.embedBatch(inputs);

        assertEquals(inputs.size(), results.size(), "result count == input count");
        for (int i = 0; i < results.size(); i++) {
            EmbedResult r = results.get(i);
            assertEquals(EXPECTED_DENSE_DIM, r.dense().length, "dense dim @" + i);
            assertFalse(r.sparse().isEmpty(), "sparse should not be empty @" + i);
        }
    }
}
