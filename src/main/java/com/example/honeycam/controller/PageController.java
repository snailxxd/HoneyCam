package com.example.honeycam.controller;

import com.example.honeycam.config.HoneyCamProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves page templates with honeypot configuration injected.
 */
@Controller
public class PageController {

    private final HoneyCamProperties props;

    public PageController(HoneyCamProperties props) {
        this.props = props;
    }

    /**
     * Camera live-view page with the 360° panorama viewer.
     * All configuration is passed via data attributes so the JS
     * viewer can pick it up without additional AJAX requests.
     */
    @GetMapping("/camera")
    public String cameraPage(Model model) {
        HoneyCamProperties.Camera     cam    = props.getCamera();
        HoneyCamProperties.Panorama   pan    = props.getPanorama();
        HoneyCamProperties.Deception  dec    = props.getDeception();
        HoneyCamProperties.Interaction inter = props.getInteraction();

        model.addAttribute("brand",                cam.getBrand());
        model.addAttribute("modelName",            cam.getModel());
        model.addAttribute("ptzEnabled",           inter.isPtzEnabled());
        model.addAttribute("panoramaMode",         pan.getMode());
        model.addAttribute("panoramaVideoUrl",     pan.getVideoUrl());
        model.addAttribute("panoramaImageUrl",     pan.getImageUrl());
        model.addAttribute("previewLatencyMinMs",  dec.getPreviewLatencyMinMs());
        model.addAttribute("previewLatencyMaxMs",  dec.getPreviewLatencyMaxMs());
        model.addAttribute("autoPatrolEnabled",    inter.isAutoPatrolEnabled());
        model.addAttribute("autoPatrolPanDegrees", inter.getAutoPatrolPanDegrees());
        model.addAttribute("autoPatrolTiltDegrees",inter.getAutoPatrolTiltDegrees());
        model.addAttribute("autoPatrolCycleSeconds",inter.getAutoPatrolCycleSeconds());
        model.addAttribute("cameraEpochStart",      cam.getEpochStart());

        return "camera";
    }
}
