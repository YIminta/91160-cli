package com.github.pengpan.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DoctorEnum {

    D200544316("流感疫苗"),
    D200546006("九价第二针"),
    D200546007("九价第三针");

    private final String doctorName;

}
