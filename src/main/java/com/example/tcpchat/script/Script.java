package com.example.tcpchat.script;

import java.nio.charset.Charset;
import java.util.List;

public record Script(Charset charset, List<Step> steps) {
}
