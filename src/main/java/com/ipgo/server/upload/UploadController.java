package com.ipgo.server.upload;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadRecordService uploads;

    public UploadController(UploadRecordService uploads) {
        this.uploads = uploads;
    }

    // U1 수불부 업로드. 같은 기간이면 409, 덮어쓰려면 overwrite=true로 다시 보낸다.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StoredUpload upload(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean overwrite, Principal principal) {
        return uploads.upload(Long.parseLong(principal.getName()), file, overwrite);
    }

    // U3 이미 올린 기간 목록
    @GetMapping("/periods")
    public UploadPeriods periods(Principal principal) {
        return uploads.periods(Long.parseLong(principal.getName()));
    }
}
