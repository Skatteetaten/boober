package no.skatteetaten.aurora.boober.utils

import spock.lang.Specification

class PropertiesUtilTest extends Specification {

  def props = '''#a text line
foo=bar
username=user
password=pass
something=sameting
'''.getBytes()

  def "Remove non-listed keys from properties"() {
    when:
      byte[] filteredBytes = PropertiesUtilKt.filterProperties(props, ['username', 'password'], [:])

    then:
      Properties filteredProps = loadProperties(filteredBytes)
      filteredProps.size() == 2
      filteredProps.stringPropertyNames().containsAll(['username', 'password'])
      !filteredProps.stringPropertyNames().containsAll(['foo', 'something'])
  }

  def "Remove correct elements when key list contain unknown keys"() {
    when:
      byte[] filteredBytes = PropertiesUtilKt.filterProperties(props, ['username', 'unknown'], [:])

    then:
      Properties filteredProps = loadProperties(filteredBytes)
      filteredProps.size() == 1
      filteredProps.stringPropertyNames()[0] == 'username'
  }

  def "Replace filtered key mappings"() {
    when:
      def filteredBytes = PropertiesUtilKt.filterProperties(props, ['username'], ['username': 'new-username'])

    then:
      Properties filteredProps = loadProperties(filteredBytes)
      filteredProps.getProperty('new-username') == 'user'
      filteredProps.size() == 1
  }

  def "Replace key mappings without filtering keys"() {
    when:
      def filteredBytes = PropertiesUtilKt.filterProperties(props, [], ['username': 'new-username'])

    then:
      Properties filteredProps = loadProperties(filteredBytes)
      filteredProps.getProperty('new-username') == 'user'
      filteredProps.size() == 4
  }

  def loadProperties(byte[] bytes) {
    Properties filteredProps = new Properties()
    filteredProps.load(new ByteArrayInputStream(bytes))
    return filteredProps
  }
}
