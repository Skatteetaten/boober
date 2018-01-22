package no.skatteetaten.aurora.boober.utils


import spock.lang.Specification

class CollectionUtilsTest extends Specification {

  final String props = "#a text line\nfoo=bar\nusername=user\npassword=pass\nsomething=sameting\n"

  def "Remove non-listed keys from properties"() {
    given:
      byte[] byteArray = props.getBytes()
      List<String> filterKeys = new ArrayList<>()
      filterKeys.add("username")
      filterKeys.add("password")

    when:
      byte[] filteredBytes = CollectionUtilsKt.filterProperties(byteArray, filterKeys)

    then:
      Properties filteredProps = new Properties()
      filteredProps.load(new ByteArrayInputStream(filteredBytes))
      filteredProps.size() == 2
      filteredProps.stringPropertyNames().contains("username")
      filteredProps.stringPropertyNames().contains("password")
      !filteredProps.stringPropertyNames().contains("foo")
      !filteredProps.stringPropertyNames().contains("something")
  }

  def "Remove everything when key list is empty"() {
    given:
      byte[] byteArray = props.getBytes()
      List<String> filterKeys = new ArrayList<>()

    when:
      byte[] filteredBytes = CollectionUtilsKt.filterProperties(byteArray, filterKeys)

    then:
      Properties filteredProps = new Properties()
      filteredProps.load(new ByteArrayInputStream(filteredBytes))
      filteredProps.size() == 0
  }

  def "Remove correct elements when key list contain unknown keys"() {
    given:
      byte[] byteArray = props.getBytes()
      List<String> filterKeys = new ArrayList<>()
      filterKeys.add("username")
      filterKeys.add("unknown")

    when:
      byte[] filteredBytes = CollectionUtilsKt.filterProperties(byteArray, filterKeys)

    then:
      Properties filteredProps = new Properties()
      filteredProps.load(new ByteArrayInputStream(filteredBytes))
      filteredProps.size() == 1
      filteredProps.stringPropertyNames().contains("username")
  }
}

