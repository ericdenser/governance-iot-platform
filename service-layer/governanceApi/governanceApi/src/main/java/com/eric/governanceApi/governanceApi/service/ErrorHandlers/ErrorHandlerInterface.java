package com.eric.governanceApi.governanceApi.service.ErrorHandlers;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.model.dto.DeviceErrorDTO;

public interface ErrorHandlerInterface {
    DeviceError handles();
    void process(DeviceErrorDTO errorDTO);
}
