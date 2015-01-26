/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import com.google.gson.reflect.TypeToken;
import java.net.URI;
import java.util.List;
import javax.inject.Named;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static feign.assertj.FeignAssertions.assertThat;
import static java.util.Arrays.asList;
import static org.assertj.core.data.MapEntry.entry;

/**
 * Tests interfaces defined per {@link Contract.Default} are interpreted into expected {@link feign
 * .RequestTemplate template}
 * instances.
 */
public class DefaultContractTest {
  @Rule public final ExpectedException thrown = ExpectedException.none();

  Contract.Default contract = new Contract.Default();

  interface Methods {
    @RequestLine("POST /") void post();

    @RequestLine("PUT /") void put();

    @RequestLine("GET /") void get();

    @RequestLine("DELETE /") void delete();
  }

  @Test public void httpMethods() throws Exception {
    assertThat(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("post")).template())
        .hasMethod("POST");

    assertThat(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("put")).template())
        .hasMethod("PUT");

    assertThat(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("get")).template())
        .hasMethod("GET");

    assertThat(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("delete")).template())
        .hasMethod("DELETE");
  }

  interface BodyParams {
    @RequestLine("POST") Response post(List<String> body);

    @RequestLine("POST") Response tooMany(List<String> body, List<String> body2);
  }

  @Test public void bodyParamIsGeneric() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(BodyParams.class.getDeclaredMethod("post", List.class));

    assertThat(md.bodyIndex())
        .isEqualTo(0);
    assertThat(md.bodyType())
        .isEqualTo(new TypeToken<List<String>>(){}.getType());
  }

  @Test public void tooManyBodies() throws Exception {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Method has too many Body");
    contract.parseAndValidatateMetadata(
        BodyParams.class.getDeclaredMethod("tooMany", List.class, List.class));
  }

  interface CustomMethod {
    @RequestLine("PATCH") Response patch();
  }

  @Test public void customMethodWithoutPath() throws Exception {
    assertThat(contract.parseAndValidatateMetadata(CustomMethod.class.getDeclaredMethod("patch")).template())
        .hasMethod("PATCH")
        .hasUrl("");
  }

  interface WithQueryParamsInPath {
    @RequestLine("GET /") Response none();

    @RequestLine("GET /?Action=GetUser") Response one();

    @RequestLine("GET /?Action=GetUser&Version=2010-05-08") Response two();

    @RequestLine("GET /?Action=GetUser&Version=2010-05-08&limit=1") Response three();

    @RequestLine("GET /?flag&Action=GetUser&Version=2010-05-08") Response empty();
  }

  @Test public void queryParamsInPathExtract() throws Exception {
    assertThat(contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("none")).template())
        .hasUrl("/")
        .hasQueries();

    assertThat(contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("one")).template())
        .hasUrl("/")
        .hasQueries(
            entry("Action", asList("GetUser"))
        );

    assertThat(contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("two")).template())
        .hasUrl("/")
        .hasQueries(
            entry("Action", asList("GetUser")),
            entry("Version", asList("2010-05-08"))
        );

    assertThat(contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("three")).template())
        .hasUrl("/")
        .hasQueries(
            entry("Action", asList("GetUser")),
            entry("Version", asList("2010-05-08")),
            entry("limit", asList("1"))
        );

    assertThat(contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("empty")).template())
        .hasUrl("/")
        .hasQueries(
            entry("flag", asList(new String[] { null })),
            entry("Action", asList("GetUser")),
            entry("Version", asList("2010-05-08"))
        );
  }

  interface BodyWithoutParameters {
    @RequestLine("POST /")
    @Headers("Content-Type: application/xml")
    @Body("<v01:getAccountsListOfUser/>") Response post();
  }

  @Test public void bodyWithoutParameters() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(BodyWithoutParameters.class.getDeclaredMethod("post"));

    assertThat(md.template())
        .hasBody("<v01:getAccountsListOfUser/>");
  }

  @Test public void producesAddsContentTypeHeader() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(BodyWithoutParameters.class.getDeclaredMethod("post"));

    assertThat(md.template())
        .hasHeaders(
            entry("Content-Type", asList("application/xml")),
            entry("Content-Length", asList(String.valueOf(md.template().body().length)))
        );
  }

  interface WithURIParam {
    @RequestLine("GET /{1}/{2}") Response uriParam(@Param("1") String one, URI endpoint, @Param("2") String two);
  }

  @Test public void withPathAndURIParam() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(
        WithURIParam.class.getDeclaredMethod("uriParam", String.class, URI.class, String.class));

    assertThat(md.indexToName())
        .containsExactly(
            entry(0, asList("1")),
            // Skips 1 as it is a url index!
            entry(2, asList("2"))
        );

    assertThat(md.urlIndex()).isEqualTo(1);
  }

  interface WithPathAndQueryParams {
    @RequestLine("GET /domains/{domainId}/records?name={name}&type={type}")
    Response recordsByNameAndType(@Param("domainId") int id, @Param("name") String nameFilter,
                                  @Param("type") String typeFilter);
  }

  @Test public void pathAndQueryParams() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(WithPathAndQueryParams.class.getDeclaredMethod
        ("recordsByNameAndType", int.class, String.class, String.class));

    assertThat(md.template())
        .hasQueries(entry("name", asList("{name}")), entry("type", asList("{type}")));

    assertThat(md.indexToName()).containsExactly(
        entry(0, asList("domainId")),
        entry(1, asList("name")),
        entry(2, asList("type"))
    );
  }

  interface FormParams {
    @RequestLine("POST /")
    @Body("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D")
    void login(
        @Param("customer_name") String customer,
        @Param("user_name") String user, @Param("password") String password);
  }

  @Test public void bodyWithTemplate() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(FormParams.class.getDeclaredMethod("login", String.class,
        String.class, String.class));

    assertThat(md.template())
        .hasBodyTemplate("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D");
  }

  @Test public void formParamsParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(FormParams.class.getDeclaredMethod("login", String.class,
        String.class, String.class));

    assertThat(md.formParams())
        .containsExactly("customer_name", "user_name", "password");

    assertThat(md.indexToName()).containsExactly(
        entry(0, asList("customer_name")),
        entry(1, asList("user_name")),
        entry(2, asList("password"))
    );
  }

  interface HeaderParams {
    @RequestLine("POST /")
    @Headers("Auth-Token: {Auth-Token}") void logout(@Param("Auth-Token") String token);
  }

  @Test public void headerParamsParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(HeaderParams.class.getDeclaredMethod("logout", String.class));

    assertThat(md.template()).hasHeaders(entry("Auth-Token", asList("{Auth-Token}")));

    assertThat(md.indexToName())
        .containsExactly(entry(0, asList("Auth-Token")));
  }

  // TODO: remove all of below in 8.x

  interface WithPathAndQueryParamsAnnotatedWithNamed {
    @RequestLine("GET /domains/{domainId}/records?name={name}&type={type}")
    Response recordsByNameAndType(@Named("domainId") int id, @Named("name") String nameFilter,
        @Named("type") String typeFilter);
  }

  @Test public void pathAndQueryParamsAnnotatedWithNamed() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(WithPathAndQueryParamsAnnotatedWithNamed.class.getDeclaredMethod
        ("recordsByNameAndType", int.class, String.class, String.class));

    assertThat(md.template())
        .hasQueries(entry("name", asList("{name}")), entry("type", asList("{type}")));

    assertThat(md.indexToName()).containsExactly(
        entry(0, asList("domainId")),
        entry(1, asList("name")),
        entry(2, asList("type"))
    );
  }

  interface FormParamsAnnotatedWithNamed {
    @RequestLine("POST /")
    @Body("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D")
    void login(
        @Named("customer_name") String customer,
        @Named("user_name") String user, @Named("password") String password);
  }

  @Test public void bodyWithTemplateAnnotatedWithNamed() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(FormParamsAnnotatedWithNamed.class.getDeclaredMethod("login", String.class,
        String.class, String.class));

    assertThat(md.template())
        .hasBodyTemplate("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D");
  }

  @Test public void formParamsAnnotatedWithNamedParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(FormParamsAnnotatedWithNamed.class.getDeclaredMethod("login", String.class,
        String.class, String.class));

    assertThat(md.formParams())
        .containsExactly("customer_name", "user_name", "password");

    assertThat(md.indexToName()).containsExactly(
        entry(0, asList("customer_name")),
        entry(1, asList("user_name")),
        entry(2, asList("password"))
    );
  }

  interface HeaderParamsAnnotatedWithNamed {
    @RequestLine("POST /")
    @Headers("Auth-Token: {Auth-Token}") void logout(@Named("Auth-Token") String token);
  }

  @Test public void headerParamsAnnotatedWithNamedParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(HeaderParamsAnnotatedWithNamed.class.getDeclaredMethod("logout", String.class));

    assertThat(md.template()).hasHeaders(entry("Auth-Token", asList("{Auth-Token}")));

    assertThat(md.indexToName())
        .containsExactly(entry(0, asList("Auth-Token")));
  }
}
