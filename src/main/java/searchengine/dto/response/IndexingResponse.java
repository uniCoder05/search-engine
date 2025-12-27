package searchengine.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@AllArgsConstructor
public class IndexingResponse {
    private final boolean result;
    private final String error;

    public IndexingResponse(boolean result) {
        this.result = result;
        this.error = null;
    }
}
