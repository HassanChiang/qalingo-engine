/**
 * Most of the code in the Qalingo project is copyrighted Hoteia and licensed
 * under the Apache License Version 2.0 (release version 0.8.0)
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *                   Copyright (c) Hoteia, 2012-2014
 * http://www.hoteia.com - http://twitter.com/hoteia - contact@hoteia.com
 *
 */
package org.hoteia.qalingo.core.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.Date;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.hibernate.internal.util.SerializationHelper;
import org.hoteia.qalingo.core.dao.GeolocDao;
import org.hoteia.qalingo.core.domain.EngineSetting;
import org.hoteia.qalingo.core.domain.GeolocAddress;
import org.hoteia.qalingo.core.domain.GeolocCity;
import org.hoteia.qalingo.core.domain.bean.GeolocData;
import org.hoteia.qalingo.core.domain.bean.GeolocDataCity;
import org.hoteia.qalingo.core.domain.bean.GeolocDataCountry;
import org.hoteia.qalingo.core.web.bean.geoloc.json.GoogleGeoCode;
import org.hoteia.qalingo.core.web.bean.geoloc.json.GoogleGeoCodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;

@Service("geolocService")
@Transactional
public class GeolocService {
    
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    
    @Autowired
    protected EmailService emailService;
    
    @Autowired
    protected EngineSettingService engineSettingService;
    
    @Autowired
    protected GeolocDao geolocDao;
    
    // COMMON
    
    public GeolocCity geolocByCityAndCountry(final String city, final String country){
        GeolocCity geolocCity = null;
        String addressParam = encodeGoogleAddress(null, null, city, country);
        GoogleGeoCode geoCode = geolocGoogleWithAddress(addressParam);
        if("OVER_QUERY_LIMIT".equals(geoCode.getStatus())){
            logger.error("API Geoloc returns message OVER_QUERY_LIMIT: " + geoCode.getErrorMessage());
            engineSettingService.flagSettingGoogleGeolocationApiOverQuota();
            return geolocCity;
        }
        
        if(geoCode != null) {
            geolocCity = new GeolocCity();
            geolocCity.setCity(city);
            geolocCity.setCountry(country);
            geolocCity.setJson(SerializationHelper.serialize(geoCode));
            geolocCity.setLatitude(geoCode.getLatitude());
            geolocCity.setLongitude(geoCode.getLongitude());
            geolocCity = geolocDao.saveOrUpdateGeolocCity(geolocCity);
        }
        return geolocCity;
    }
    
    public GeolocAddress geolocByAddress(final String address, final String postalCode, final String city, final String country){
        GeolocAddress geolocAddress = null;
        String formatedAddress = encodeGoogleAddress(address, postalCode, city, country);
        GoogleGeoCode geoCode = geolocGoogleWithAddress(formatedAddress);
        if("OVER_QUERY_LIMIT".equals(geoCode.getStatus())){
            logger.error("API Geoloc returns message OVER_QUERY_LIMIT: " + geoCode.getErrorMessage());
            engineSettingService.flagSettingGoogleGeolocationApiOverQuota();
            return geolocAddress;
        }
        
        if(geoCode != null) {
            geolocAddress = new GeolocAddress();
            geolocAddress.setAddress(address);
            geolocAddress.setPostalCode(postalCode);
            geolocAddress.setCity(city);
            geolocAddress.setCountry(country);
            geolocAddress.setJson(SerializationHelper.serialize(geoCode));
            geolocAddress.setFormatedAddress(formatedAddress);
            geolocAddress.setLatitude(geoCode.getLatitude());
            geolocAddress.setLongitude(geoCode.getLongitude());
            geolocAddress = geolocDao.saveOrUpdateGeolocAddress(geolocAddress);
        }
        return geolocAddress;
    }
    
    public GeolocAddress geolocByLatitudeLongitude(final String latitude, final String longitude) {
        GeolocAddress geolocAddress = null;
        GoogleGeoCode geoCode = geolocGoogleWithLatitudeLongitude(latitude, longitude);
        if("OVER_QUERY_LIMIT".equals(geoCode.getStatus())){
            logger.error("API Geoloc returns message OVER_QUERY_LIMIT: " + geoCode.getErrorMessage());
            engineSettingService.flagSettingGoogleGeolocationApiOverQuota();
            return geolocAddress;
        }
        
        if(geoCode != null) {
            GoogleGeoCodeResult googleGeoCodeResult = geoCode.getResults().get(0);
            String formatedAdress = googleGeoCodeResult.getFormattedAddress();
            formatedAdress = formatedAdress.replace(" ", "+");
            geolocAddress = new GeolocAddress();
            geolocAddress.setAddress(googleGeoCodeResult.getAddress());
            geolocAddress.setPostalCode(googleGeoCodeResult.getPostalCode());
            geolocAddress.setCity(googleGeoCodeResult.getCity());
            geolocAddress.setCountry(googleGeoCodeResult.getCountryCode());
            geolocAddress.setJson(SerializationHelper.serialize(geoCode));
            geolocAddress.setFormatedAddress(formatedAdress);
            geolocAddress.setLatitude(latitude);
            geolocAddress.setLongitude(longitude);
            geolocAddress = geolocDao.saveOrUpdateGeolocAddress(geolocAddress);
        }
        return geolocAddress;
    }
    
    public GoogleGeoCode geolocGoogleWithAddress(final String formatedAddress){
        GoogleGeoCode geoCode = null;
        boolean googleGelocIsOverQuota;
        try {
            googleGelocIsOverQuota = engineSettingService.isGoogleGeolocationApiStillOverQuotas(new Date());
            if (googleGelocIsOverQuota == false) {
                String key = null;
                try {
                    key = engineSettingService.getGoogleGeolocationApiKey();
                } catch (Exception e) {
                    logger.error("Google Geolocation API Key is mandatory!", e);
                }
                if (key != null && StringUtils.isNotEmpty(key)) {
                    HttpPost httpPost = new HttpPost("https://maps.googleapis.com/maps/api/geocode/json?address=" + formatedAddress + "&key=" + key);
                    HttpClient httpClient = new DefaultHttpClient();
                    HttpResponse httpResponse = httpClient.execute(httpPost);

                    BufferedReader streamReader = new BufferedReader(new InputStreamReader(httpResponse.getEntity().getContent()));
                    StringBuilder responseStrBuilder = new StringBuilder();

                    String inputStr;
                    while ((inputStr = streamReader.readLine()) != null) {
                        responseStrBuilder.append(inputStr);
                    }
                    String json = responseStrBuilder.toString();

                    ObjectMapper mapper = new ObjectMapper();
                    geoCode = mapper.readValue(json, GoogleGeoCode.class);
                }
            } else {
                logger.warn("Google Geolocation API still over Quota! We can't use geolocation for this address: " + formatedAddress);
            }
        } catch (ClientProtocolException e) {
            logger.error("", e);
        } catch (IOException e) {
            logger.error("", e);
        } catch (IllegalStateException e) {
            logger.error("", e);
        } catch (ParseException e) {
            logger.error("", e);
        }
        return geoCode;
    }
    
    public GoogleGeoCode geolocGoogleWithLatitudeLongitude(final String latitude, final String longitude){
        GoogleGeoCode geoCode = null;
        boolean googleGelocIsOverQuota;
        try {
            googleGelocIsOverQuota = engineSettingService.isGoogleGeolocationApiStillOverQuotas(new Date());
            String paramLatLong = latitude.trim() + "," + longitude.trim();
            if (googleGelocIsOverQuota == false) {
                String key = null;
                try {
                    key = engineSettingService.getGoogleGeolocationApiKey();
                } catch (Exception e) {
                    logger.error("Google Geolocation API Key is mandatory!", e);
                }
                if (key != null && StringUtils.isNotEmpty(key)) {
                    HttpPost request = new HttpPost("https://maps.googleapis.com/maps/api/geocode/json?latlng=" + paramLatLong + "&key=" + key);
                    HttpClient httpClient = new DefaultHttpClient();
                    HttpResponse response = httpClient.execute(request);

                    BufferedReader streamReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                    StringBuilder responseStrBuilder = new StringBuilder();

                    String inputStr;
                    while ((inputStr = streamReader.readLine()) != null) {
                        responseStrBuilder.append(inputStr);
                    }
                    String json = responseStrBuilder.toString();

                    ObjectMapper mapper = new ObjectMapper();
                    geoCode = mapper.readValue(json, GoogleGeoCode.class);
                }
            } else {
                logger.warn("Google Geolocation API still over Quota! We can't use geolocation for this lat/long: " + paramLatLong);
            }
        } catch (ClientProtocolException e) {
            logger.error("", e);
        } catch (IOException e) {
            logger.error("", e);
        } catch (IllegalStateException e) {
            logger.error("", e);
        } catch (ParseException e) {
            logger.error("", e);
        }
        return geoCode;
    }
    
    public String encodeGoogleAddress(final String address, final String postalCode, final String city, final String country) {
        StringBuffer encode  = new StringBuffer();
        if(StringUtils.isNotEmpty(address)){
            encode.append(address.replace(" ", "+"));
            encode.append(",");
        }
        if(StringUtils.isNotEmpty(city)){
            encode.append(city.replace(" ", "+"));
            encode.append(",");
        }
        if(StringUtils.isNotEmpty(postalCode)){
            encode.append(postalCode.replace(" ", "+"));
            encode.append(",");
        }
        if(StringUtils.isNotEmpty(country)){
            encode.append(country.replace(" ", "+"));
            encode.append(",");
        }
        return encode.toString();
    }
    
    // GEOLOC CITY
    
    public GeolocCity getGeolocCityByCityAndCountry(final String city, final String country, Object... params) {
        return geolocDao.getGeolocCityByCityAndCountry(city, country, params);
    }
    
    public GeolocCity saveOrUpdateGeolocCity(final GeolocCity geolocCity) {
        return geolocDao.saveOrUpdateGeolocCity(geolocCity);
    }
    
    public void deleteGeolocCity(final GeolocCity geolocCity) {
        geolocDao.deleteGeolocCity(geolocCity);
    }
    
    // GEOLOC ADDRESS
    
    public GeolocAddress getGeolocAddressByFormatedAddress(final String formatedAddress, Object... params) {
        return geolocDao.getGeolocAddressByFormatedAddress(formatedAddress, params);
    }

    public GeolocAddress getGeolocAddressByLatitudeAndLongitude(final String latitude, final String longitude, Object... params) {
        return geolocDao.getGeolocAddressByLatitudeAndLongitude(latitude, longitude, params);
    }

    public GeolocAddress saveOrUpdateGeolocAddress(final GeolocAddress geolocCity) {
        return geolocDao.saveOrUpdateGeolocAddress(geolocCity);
    }
    
    public void deleteGeolocAddress(final GeolocAddress geolocCity) {
        geolocDao.deleteGeolocAddress(geolocCity);
    }
    
    /**
     * 
     */
    public GeolocData getGeolocData(final String remoteAddress) throws Exception {
        GeolocData geolocData = null;
        if(!remoteAddress.equals("127.0.0.1")){
            geolocData = new GeolocData();
            final Country country = geolocAndGetCountry(remoteAddress);
            geolocData.setRemoteAddress(remoteAddress);
            if(country != null 
                    && StringUtils.isNotEmpty(country.getIsoCode())){
                GeolocDataCountry geolocDataCountry = new GeolocDataCountry();
                geolocDataCountry.setIsoCode(country.getIsoCode());
                geolocDataCountry.setName(country.getName());
                geolocData.setCountry(geolocDataCountry);
                final City city = geolocAndGetCity(remoteAddress);
                GeolocDataCity geolocDataCity = new GeolocDataCity();
                geolocDataCity.setGeoNameId(city.getGeoNameId());
                geolocDataCity.setName(city.getName());
                geolocData.setCity(geolocDataCity);
            }
        }
        return geolocData;
    }
    
    /**
     * 
     */
    public String geolocAndGetCountryIsoCode(final String customerRemoteAddr) throws Exception {
        final Country country = geolocAndGetCountry(customerRemoteAddr);
        return country.getIsoCode();
    }
    
    /**
     * 
     */
    public Country geolocAndGetCountry(final String customerRemoteAddr) throws Exception {
        try {
            final InetAddress address = InetAddress.getByName(customerRemoteAddr);
            
            final DatabaseReader databaseReader = new DatabaseReader.Builder(getCountryDataBase()).build();
            final CountryResponse countryResponse = databaseReader.country(address);
            if(countryResponse != null){
                return countryResponse.getCountry();
            }
        } catch (AddressNotFoundException e) {
            logger.warn("Geoloc country, can't find this address:" + customerRemoteAddr);
        } catch (FileNotFoundException e) {
            logger.error("Geoloc country, can't find database MaxMind", e);
        } catch (Exception e) {
            logger.error("Geoloc country, exception to find country with this address:" + customerRemoteAddr, e);
        }
        return null;
    }
    
    /**
     * 
     */
    public String geolocAndGetCityName(final String customerRemoteAddr) throws Exception {
        final City city = geolocAndGetCity(customerRemoteAddr);
        return city.getName();
    }
    
    /**
     * 
     */
    public City geolocAndGetCity(final String customerRemoteAddr) throws Exception {
        try {
            final InetAddress address = InetAddress.getByName(customerRemoteAddr);
            
            final DatabaseReader databaseReader = new DatabaseReader.Builder(getCityDataBase()).build();

            final CityResponse cityResponse = databaseReader.city(address);
            if(cityResponse != null){
                return cityResponse.getCity();
                
            }
        } catch (AddressNotFoundException e) {
            logger.warn("Geoloc city, can't find this address:" + customerRemoteAddr);
        } catch (FileNotFoundException e) {
            logger.error("Geoloc city, can't find database MaxMind", e);
        } catch (Exception e) {
            logger.error("Geoloc city, can't find this city with this address:" + customerRemoteAddr, e);
        }
        return null;
    }
    
    protected File getCityDataBase(){
        EngineSetting engineSetting = engineSettingService.getSettingGeolocCityFilePath();
        final File database = new File(engineSetting.getDefaultValue());
        return database;
    }
    
    protected File getCountryDataBase(){
        EngineSetting engineSetting = engineSettingService.getSettingGeolocCountryFilePath();
        final File database = new File(engineSetting.getDefaultValue());
        return database;
    }
    
}