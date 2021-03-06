package services.impl;

import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;
import dto.DatasetDescription;
import lombok.extern.slf4j.Slf4j;
import play.api.libs.json.JsObject;
import play.libs.Json;
import play.libs.exception.ExceptionUtils;
import play.mvc.Http;
import scala.util.parsing.json.JSONObject;
import scala.util.parsing.json.JSONObject$;
import services.DatasetService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Singleton
public class DatasetServiceImpl implements DatasetService {
    private final Config config;

    @Inject
    public DatasetServiceImpl(Config config) {
        this.config = config;
    }

    @Override
    public Path getUploadDirectory() throws IOException {
        String uploadDir = config.getString("upload-dataset.directory-name");
        Path p = Paths.get(uploadDir).toAbsolutePath();
        if (Files.notExists(p)) {
            Files.createDirectories(p);
        }
        return p;
    }

    @Override
    public List<DatasetDescription> listDataSet(boolean ascending) {
        try {
            Comparator<DatasetDescription> c = Comparator.comparing(DatasetDescription::getUploadDate);
            if (!ascending) {
                c = c.reversed();
            }
            Path uploadDir = getUploadDirectory();
            try (Stream<Path> paths = Files.walk(Paths.get(uploadDir.toString()))) {
                return paths
                        .filter(Files::isRegularFile)
                        .map(this::getFileDesc)
                        .sorted(c)
                        .collect(Collectors.toList());
            }
        } catch (IOException e1) {
            log.error("??????????????????????????????, ????????????: {}", ExceptionUtils.getStackTrace(e1));
            return Collections.emptyList();
        }
    }

    @Override
    public List<DatasetDescription> listSortedDataSet(String sortAttr, boolean ascending) {
        Comparator<DatasetDescription> byStringAttr = Comparator.comparing(d -> this.<String>getTattr(d, sortAttr));
        Comparator<DatasetDescription> byIntAttr = Comparator.comparing(d -> this.<Integer>getTattr(d, sortAttr));
        Comparator<DatasetDescription> byLongAttr = Comparator.comparing(d -> this.<Long>getTattr(d, sortAttr));

        Set<String> stringAttrSet = ImmutableSet.of("name", "recordStartDate", "recordEndDate", "uploadDate");
        Set<String> intAttrSet = ImmutableSet.of("recordNum");
        Set<String> longAttrSet = ImmutableSet.of("fileSize");

        Comparator<DatasetDescription> comparator = null;
        // ??????????????????????????????????????? Comparator
        if (stringAttrSet.contains(sortAttr)) {
            comparator = byStringAttr;
        } else if (intAttrSet.contains(sortAttr)) {
            comparator = byIntAttr;
        } else if (longAttrSet.contains(sortAttr)) {
            comparator = byLongAttr;
        } else {
            log.error("sortAttr??????: {}", sortAttr);
            return listDataSet(ascending);
        }

        if (!ascending) {
            comparator = comparator.reversed();
        }

        try {
            Path uploadDir = getUploadDirectory();
            try (Stream<Path> paths = Files.walk(Paths.get(uploadDir.toString()))) {
                return paths
                        .filter(Files::isRegularFile)
                        .map(this::getFileDesc)
                        .sorted(comparator)
                        .collect(Collectors.toList());
            }
        } catch (IOException e1) {
            log.error("???????????????????????????????????????, ????????????: {}", ExceptionUtils.getStackTrace(e1));
            return Collections.emptyList();
        }
    }

    @Override
    public Path getDatasetPath(String datasetName) {
        try {
            Path uploadDir = getUploadDirectory();
            return Paths.get(uploadDir.toString(), datasetName);
        } catch (IOException e) {
            log.error("??????dataset????????????, ????????????: {}", ExceptionUtils.getStackTrace(e));
            return null;
        }
    }

    @Override
    public boolean saveDataset(Http.MultipartFormData.FilePart<play.libs.Files.TemporaryFile> dataset) {
        // ?????????????????? dataset ?????? null
        try {
            String fileName = dataset.getFilename();
            play.libs.Files.TemporaryFile file = dataset.getRef();
            Path uploadDir = getUploadDirectory();
            Path p = Paths.get(uploadDir.toString(), fileName);
            file.copyTo(p, true);
            return true;
        } catch (IOException e1) {
            log.error("?????????????????????????????????, ????????????: {}", ExceptionUtils.getStackTrace(e1));
            return false;
        } catch (Exception e2) {
            log.error("??????????????????????????????, ????????????: {}", ExceptionUtils.getStackTrace(e2));
            return false;
        }
    }

    @Override
    public String getToken() {
        return config.getString("upload-dataset.token");
    }

    @Override
    public String getAllTrajectoryByMode() throws IOException {
        Path uploadDir = getUploadDirectory();
        List<Map<String, String>> allTrajectory = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(uploadDir.toString()))) {
            paths.filter(Files::isRegularFile)
                    .forEach(text -> {
                        try {
                            List<String> lines = Files.readAllLines(text);
                            String mode = "";
                            List<List<Double>> coordinates = new ArrayList<>();
                            Map<String, String> result = new HashMap<>();
                            for (int i = 8; i < lines.size(); i++) {
                                String[] mrs = lines.get(i).split(",");
                                double lon = Double.parseDouble(mrs[2]);
                                double lat = Double.parseDouble(mrs[3]);
                                //?????????MR???????????????????????????????????????
                                if (mode.equals(mrs[1]) || mode.equals("")) {
                                    mode = mrs[1];
                                    if (lon > 0.00001 && lat > 0.00001)
                                        coordinates.add(Arrays.asList(lon, lat));
                                }
                                //????????????????????????????????????
                                else {
                                    result.put(mode, coordinates.toString());
                                    mode = mrs[1];
                                    coordinates.clear();
                                    if (lon > 0.00001 && lat > 0.00001)
                                        coordinates.add(Arrays.asList(lon, lat));
                                }
                            }
                            result.put(mode, coordinates.toString());
                            allTrajectory.add(result);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }

        return allTrajectory.toString().replace("=", ":");
    }

    ;

    @Override
    public String getTrajectory(String datasetName) throws IOException {
        Path path = getDatasetPath(datasetName);
        List<String> lines = Files.readAllLines(path);
        List<List<Double>> coordinates = new ArrayList<>();
        for (int i = 8; i < lines.size(); i++) {
            String[] mrs = lines.get(i).split(",");
            double lon = Double.parseDouble(mrs[2]);
            double lat = Double.parseDouble(mrs[3]);
            if (lon > 0.00001 && lat > 0.00001)
                coordinates.add(Arrays.asList(lon, lat));
        }
        return coordinates.toString();
    }

    ;

    /* private methods */

    /**
     * ???????????????????????????????????????????????????????????????????????????????????? Android APP ???????????????????????????????????????????????????
     *
     * @param p ??????
     * @return ?????????????????????
     */
    private DatasetDescription getFileDesc(Path p) {
        String name = p.toFile().getName();

        DateFormat df1 = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        DateFormat df2 = new SimpleDateFormat("yyyy/MM/dd HH:mm");
        // ????????????
        int recordNum = -1;
        String startDate = "", endData = "", uploadDate = "";
        long size = -1;
        boolean modified = false;

        /*
         * ??????????????????????????????
         * ?????????????????????name???????????????{?????????}_{?????????????????????}_{?????????????????????????????????}_{?????????????????????????????????}.txt
         * ???????????????-17??????_211_1711051829_1711051833.txt
         */
        String reversed = new StringBuilder(name).reverse().toString();
        String[] stats = reversed.split("_", 4);
        try {
            recordNum = Integer.parseInt(new StringBuilder(stats[2]).reverse().toString());
        } catch (Exception e1) {
            log.warn("??????recordNum??????: {}", name);
        }
        DateFormat df3 = new SimpleDateFormat("yyMMddHHmm");
        try {
            Date sd = df3.parse(new StringBuilder(stats[1]).reverse().toString());
            startDate = df2.format(sd);
        } catch (Exception e2) {
            log.warn("??????startDate??????: {}", name);
        }
        try {
            Date ed = df3.parse(new StringBuilder(stats[0].substring(4)).reverse().toString());
            endData = df2.format(ed);
        } catch (Exception e3) {
            log.warn("??????endData??????: {}", name);
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            size = attrs.size();
            FileTime createTime = attrs.creationTime();
            FileTime modifyTime = attrs.lastModifiedTime();
            modified = createTime.compareTo(modifyTime) != 0;
            uploadDate = df1.format(createTime.toMillis());
        } catch (Exception e4) {
            log.error("????????????????????????????????????, ????????????: {}", ExceptionUtils.getStackTrace(e4));
        }

        return new DatasetDescription(name, recordNum, startDate, endData, uploadDate, size);
    }

    /**
     * ????????????????????????????????????????????????????????????????????????
     *
     * @param obj  ????????????????????????
     * @param attr ?????????
     * @param <T>  ????????????????????????
     * @return ????????????????????????
     */
    private <T> T getTattr(Object obj, String attr) {
        Object value = getAttr(obj, attr);
        @SuppressWarnings("unchecked")
        T t = (T) value;
        return t;
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param obj  ????????????????????????
     * @param attr ?????????
     * @return Object ??????????????????
     */
    private Object getAttr(Object obj, String attr) {
        try {
            Field field = obj.getClass().getDeclaredField(attr);
            field.setAccessible(true);
            return field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
